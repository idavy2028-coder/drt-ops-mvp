# C1 视频声明隔离恢复流程（2026-09-12）

## 版本与适用边界

API 新增 `V22__create_video_declaration_observations.sql`；gateway 自己的 H2/Flyway 序列新增 `V6__add_capability_declaration_outbox_kind.sql`。两套数据库和历史表独立，API V21 及所有既有迁移保持不变。本流程仅用于本次创建的隔离数据库，不适用于云端或共享业务库。

## 迁移前

1. 记录实例绝对数据目录、拥有者、启动进程/PID创建时间、回环地址/端口、Flyway历史、源码及产物摘要。禁止仅凭端口判定实例归属。
2. 停止隔离API/gateway写入者。API保存V21的完整custom-format pg_dump；gateway关闭数据库后保存离线数据库副本，或在没有业务写入者时执行H2 SCRIPT备份。备份与测试凭据存放在仅codex可读的私有目录，不加入Git。
3. 核对备份摘要、备份能读取，保留V21 API源码/产物和V5 gateway产物。不要覆盖旧备份。

## 验证失败时

1. 停止本轮写入者及已证明归属的实例，保留失败日志/数据库。不要执行Flyway clean，不删除迁移历史，不在失败数据库内手工删表伪装回退。
2. PostgreSQL恢复到**新建的隔离数据库或实例**：使用同版本工具和匹配数据库角色。示例命令中的端口、备份路径和数据库名必须来自本次所有权记录；密码通过受保护的PGPASSFILE或仅子进程环境提供，不写命令行。

```powershell
& (Join-Path $c1PgBin "createdb.exe") -h 127.0.0.1 -p $c1OwnedPort -U alarm_authority video_restore
& (Join-Path $c1PgBin "pg_restore.exe") -h 127.0.0.1 -p $c1OwnedPort -U alarm_authority --exit-on-error -d video_restore $c1V21Backup
```

3. H2恢复到全新路径：用RunScript导入V5 SCRIPT备份，或使用已关闭数据库的离线副本。测试代码为`VideoDeclarationDispatchTest.restoresV5SnapshotIntoSeparateDatabase`，验证版本V5、原队列记录及旧约束；不在V6文件上执行降级SQL。
4. PostgreSQL核对Flyway末版V21且无video_declaration_observations；H2核对V5及原队列数据。只把本地测试运行器指向恢复库；恢复应用时选择匹配的V21/V5旧产物，避免新版应用自动再次迁移。
5. 确认进程和端口停止后结束。源码/产物切回及应用启动回退须单独记录；本轮自动测试证明数据库恢复，不冒充生产回滚演练。

## 已执行的恢复证据

`PostgresVideoDeclarationMigrationTest.migrationAndRestoreToSeparateV21Database` 从新建实例的V21备份恢复到同实例的另一隔离库video_restore，验证Flyway为21且新观测表不存在。监督脚本核对原始Popen、PID创建时间、exe、postmaster.pid与绝对数据路径后停止数据库。成功记录位于私有目录`.tmp/c1-pg-audit-9e979358b71d422fae4f8c5630cf9b05/result.json`；2项测试全过、schema22/true、PostgresStopped=true、61166监听0。

早期ACL失败没有启动数据库；全库Hibernate validate试验发现既有CHAR/VARCHAR映射差异，未修复。实际API配置为ddl-auto=none；后续按该配置验证并显式检查V22 JSONB与外键，不能宣称全库validate已通过。
