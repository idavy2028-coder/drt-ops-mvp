package com.idavy.drtops.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bootstrap_admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "drt.auth.jwt-secret=01234567890123456789012345678901",
        "drt.auth.bootstrap-admin-username=admin01",
        "drt.auth.bootstrap-admin-password=Secret123!"
})
class BootstrapAdminInitializerTest {

    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BootstrapAdminInitializer initializer;

    @BeforeEach
    void resetUserStore() {
        users.deleteAll();
    }

    @Test
    void createsOnlyOneAdministratorWhenTheUserStoreStartsEmpty() throws Exception {
        initializer.run(new DefaultApplicationArguments());
        UserAccount administrator = users.findByUsernameIgnoreCase("admin01").orElseThrow();

        assertThat(administrator.getRoles()).containsExactly(RoleCode.SYSTEM_ADMIN);
        assertThat(passwordEncoder.matches("Secret123!", administrator.getPasswordHash())).isTrue();

        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void skipsBootstrapWhenTheUserStoreAlreadyContainsAnAccount() throws Exception {
        users.save(UserAccount.create("operator01", "operator", passwordEncoder.encode("Secret123!")));

        initializer.run(new DefaultApplicationArguments());

        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByUsernameIgnoreCase("admin01")).isEmpty();
    }
}
