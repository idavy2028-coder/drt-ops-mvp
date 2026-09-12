package com.idavy.drtops.domain.onboard;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="drt.integration.postgis",matches="true")
class PostgresVideoDeclarationMigrationTest extends VideoDeclarationIngressServiceTest {
 static final String PREFIX="drt.integration.alarm-postgis.";
 static String url,user,password; static Path run; static int port;
 @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
  url=System.getProperty(PREFIX+"jdbc-url","");user=System.getProperty(PREFIX+"username","");password=System.getProperty(PREFIX+"password","");
  if(!Boolean.getBoolean(PREFIX+"external-ephemeral") || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/alarm_authority") || !user.equals("alarm_authority")) throw new IllegalStateException("isolated DB guard");
  port=Integer.parseInt(url.substring(url.lastIndexOf(':')+1,url.lastIndexOf('/')));
  run=Path.of(System.getProperty("drt.video.run-directory")).toAbsolutePath();
  if(!Files.isDirectory(run) || !run.getFileName().toString().startsWith("c1-pg-audit-")) throw new IllegalStateException("isolated evidence directory guard");
  Flyway.configure().dataSource(url,user,password).target("19").load().migrate();
  try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){
   st.executeUpdate("update vehicles set dispatchable=false");
   st.executeUpdate("update jt_terminals set status='SUSPENDED' where status='ACTIVE' and not exists (select 1 from onboard_device_memberships m join onboard_systems s on s.id=m.onboard_system_id where m.terminal_id=jt_terminals.id and m.status='ACTIVE' and m.valid_to is null and s.status='ACTIVE')");
  }
  Flyway.configure().dataSource(url,user,password).target("21").load().migrate();
  pg("pg_dump.exe","-Fc","-f",run.resolve("before-v22.dump").toString(),"-d","alarm_authority");
  registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->user);registry.add("spring.datasource.password",()->password);
  registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");registry.add("spring.flyway.enabled",()->"true");registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
 }
 @Test void migrationAndRestoreToSeparateV21Database() throws Exception {
  assertThat(jdbc.queryForObject("select version from flyway_schema_history order by installed_rank desc limit 1",String.class)).isEqualTo("22");
  assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema='public' and table_name='video_declaration_observations' and column_name='payload'",String.class)).isEqualTo("jsonb");
  assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema='public' and table_name='video_declaration_observations' and constraint_type='FOREIGN KEY'",Integer.class)).isEqualTo(2);
  pg("createdb.exe","video_restore");pg("pg_restore.exe","--exit-on-error","-d","video_restore",run.resolve("before-v22.dump").toString());
  try(var c=DriverManager.getConnection(url.replace("/alarm_authority","/video_restore"),user,password);var st=c.createStatement()){
   try(var r=st.executeQuery("select version from flyway_schema_history order by installed_rank desc limit 1")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("21");}
   try(var r=st.executeQuery("select to_regclass('video_declaration_observations')")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isNull();}
  }
 }
 static void pg(String executable,String... args) throws Exception {
  var command=new ArrayList<String>(List.of("C:/Program Files/PostgreSQL/17/bin/"+executable,"-h","127.0.0.1","-p",Integer.toString(port),"-U",user));command.addAll(List.of(args));
  var b=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(run.resolve(executable+".log").toFile());b.environment().put("PGPASSWORD",password);
  var p=b.start();if(!p.waitFor(60,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("PG tool timeout");}assertThat(p.exitValue()).isZero();
 }
}
