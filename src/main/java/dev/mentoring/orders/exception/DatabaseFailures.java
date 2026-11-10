package dev.mentoring.orders.exception;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.*;
import org.springframework.transaction.TransactionSystemException;

/** 인증과 주문 처리에 공통으로 사용하는 DB 장애 분류. 롤백 오류에 가려진 원인도 확인. */
public final class DatabaseFailures {
    private DatabaseFailures() { }

    /** 연결 단절, 재시작, 잠금 제한 또는 재실행 가능한 트랜잭션 실패인지 판정. */
    public static boolean isUnavailable(Throwable error) {
        return causes(error).stream().anyMatch(cause -> {
            if (cause instanceof SQLTransientConnectionException) return true;
            if (!(cause instanceof SQLException sql)) return false;
            String state = sql.getSQLState();
            return state != null && (state.startsWith("08") || state.startsWith("40")
                    || Set.of("55P03", "57014", "57P01", "57P02", "57P03").contains(state));
        });
    }

    /** 로그에 쓸 고정 길이 SQLSTATE만 반환. 예외 메시지와 SQL 원문은 포함하지 않음. */
    public static String safeSqlState(Throwable error) {
        for (Throwable cause : causes(error)) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.matches("[0-9A-Z]{5}")) return state;
            }
        }
        return "none";
    }

    /** 원인, 원래 트랜잭션 오류와 보조 예외를 중복 없이 확인. */
    private static List<Throwable> causes(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        var result = new ArrayList<Throwable>();
        if (error != null) pending.add(error);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!seen.add(current)) continue;
            result.add(current);
            if (current.getCause() != null) pending.add(current.getCause());
            if (current instanceof TransactionSystemException transaction && transaction.getOriginalException() != null) {
                pending.add(transaction.getOriginalException());
            }
            if (current instanceof SQLException sql && sql.getNextException() != null) pending.add(sql.getNextException());
            Collections.addAll(pending, current.getSuppressed());
        }
        return result;
    }
}
