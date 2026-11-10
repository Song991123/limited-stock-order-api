package dev.mentoring.orders.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mentoring.orders.repository.CustomerAccountRepository;
import dev.mentoring.orders.repository.model.LoginAccount;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** 계정 조회, 권한 변환과 계정 없음/DB 장애 구분 확인. */
@ExtendWith(MockitoExtension.class)
class CustomerUserDetailsServiceTest {
    @Mock CustomerAccountRepository repository;

    @Test
    void returnsStoredCredentialsAndCustomerAuthority() {
        when(repository.findByUsername("alice")).thenReturn(Optional.of(new LoginAccount("alice", "stored-hash")));

        var details = new CustomerUserDetailsService(repository).loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("stored-hash");
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CUSTOMER");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
        verify(repository).findByUsername("alice");
    }

    @Test
    void missingAccountIsRejectedAsUnknownCustomer() {
        when(repository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new CustomerUserDetailsService(repository).loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class).hasMessage("Unknown customer");
    }

    @Test
    void storageFailureIsNotConvertedToMissingAccount() {
        var failure = new DataAccessResourceFailureException("fixture database unavailable");
        when(repository.findByUsername("alice")).thenThrow(failure);

        assertThatThrownBy(() -> new CustomerUserDetailsService(repository).loadUserByUsername("alice"))
                .isSameAs(failure);
    }
}
