package com.rag.pipeline.common.config;

import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgConnection;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Spring AI 1.0.0-M3가 사용하는 PGvector 객체를 PostgreSQL JDBC가
 * 실제 vector OID로 해석할 수 있도록 각 물리 커넥션의 TypeInfo에 등록한다.
 *
 * HikariCP가 커넥션을 교체해도 첫 checkout 시 다시 등록하며,
 * 같은 물리 커넥션에서는 TypeInfo 단위로 한 번만 처리한다.
 */
@Slf4j
@Component
public class PgVectorConfig implements BeanPostProcessor {

    private static final String VECTOR_OID_SQL = """
            SELECT oid, typarray
            FROM pg_catalog.pg_type
            WHERE typname = 'vector'
            """;

    private final Map<TypeInfo, Boolean> registeredTypeInfos =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof HikariDataSource hikari)) return bean;

        log.info("pgvector DataSource 래퍼 적용 — beanName: {}", beanName);

        return new DelegatingDataSource(hikari) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection conn = super.getConnection();
                registerSilently(conn);
                return conn;
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                Connection conn = super.getConnection(username, password);
                registerSilently(conn);
                return conn;
            }

            private void registerSilently(Connection conn) {
                try {
                    registerVectorType(conn);
                } catch (Exception e) {
                    log.warn("pgvector JDBC 타입 등록 실패: {}", e.getMessage());
                }
            }
        };
    }

    private void registerVectorType(Connection connection) throws SQLException {
        PgConnection pgConnection = connection.unwrap(PgConnection.class);
        TypeInfo typeInfo = pgConnection.getTypeInfo();
        synchronized (registeredTypeInfos) {
            if (registeredTypeInfos.containsKey(typeInfo)) return;

            try (PreparedStatement statement = connection.prepareStatement(VECTOR_OID_SQL);
                 ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    log.warn("pg_type 에서 'vector' 타입을 찾을 수 없음 — pgvector extension 설치 여부 확인");
                    return;
                }

                int vectorOid = resultSet.getInt("oid");
                int vectorArrayOid = resultSet.getInt("typarray");
                typeInfo.addCoreType(
                        "vector",
                        vectorOid,
                        Types.OTHER,
                        PGvector.class.getName(),
                        vectorArrayOid
                );
                registeredTypeInfos.put(typeInfo, Boolean.TRUE);
                log.info("pgvector OID 확인 — oid: {}", vectorOid);
            }
        }
    }
}
