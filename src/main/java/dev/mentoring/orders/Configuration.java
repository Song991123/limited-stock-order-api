package dev.mentoring.orders;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.context.annotation.Configuration
public class Configuration {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(JdbcClient jdbc) {
        return username -> jdbc.sql("SELECT username,password_hash FROM buyers WHERE username=:username")
                .param("username", username).query((rs, row) -> User.withUsername(rs.getString(1))
                        .password(rs.getString(2)).roles("CUSTOMER").build()).optional()
                .orElseThrow(() -> new UsernameNotFoundException("Unknown customer"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll().anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults()).csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.disable()).formLogin(form -> form.disable()).build();
    }
    @Bean ApplicationRunner fixture(JdbcClient jdbc, PasswordEncoder passwords, TransactionTemplate transaction,
            @Value("${stock.demo.alice-password}") String alice, @Value("${stock.demo.bob-password}") String bob) {
        return args -> transaction.executeWithoutResult(status -> {
            for (var credentials : new String[][] {{"alice", alice}, {"bob", bob}}) {
                if (credentials[1].length() < 12) throw new IllegalStateException("Use generated demo passwords");
                jdbc.sql("INSERT INTO buyers(username,password_hash) VALUES (:username,:hash) ON CONFLICT (username) DO NOTHING")
                        .param("username", credentials[0]).param("hash", passwords.encode(credentials[1])).update();
            }
            if (jdbc.sql("SELECT count(*) FROM products").query(Long.class).single() == 0) {
                for (String name : new String[] {"Limited notebook", "Limited mug"}) {
                    long id = jdbc.sql("INSERT INTO products(name) VALUES (:name) RETURNING id").param("name", name).query(Long.class).single();
                    jdbc.sql("INSERT INTO inventories(product_id,initial_quantity,available_quantity) VALUES (:id,10,10)").param("id", id).update();
                }
            }
        });
    }
}
