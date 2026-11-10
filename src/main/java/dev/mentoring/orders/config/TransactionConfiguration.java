package dev.mentoring.orders.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 커밋 중 연결 오류를 추가 롤백 오류로 대체하지 않도록 JDBC 트랜잭션 관리자 지정. */
@Configuration(proxyBeanMethods = false)
public class TransactionConfiguration {
    /**
     * JdbcClient와 같은 DataSource 사용. 격리 수준과 저장 범위는 서비스의 Transactional 설정 유지.
     * 커밋 실패는 원래 SQLException을 보존하며, 끊긴 연결에 추가 롤백을 시도하지 않음.
     */
    @Bean
    DataSourceTransactionManager transactionManager(DataSource dataSource) {
        var manager = new DataSourceTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(false);
        return manager;
    }
}
