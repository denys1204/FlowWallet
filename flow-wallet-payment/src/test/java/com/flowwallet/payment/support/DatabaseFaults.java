package com.flowwallet.payment.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Makes one write fail or wait inside the service's own transaction: a trigger acts on the rows of one key only, so
 * other tests' writes go through. A failure stands in for a lost connection or a full disk; a delay holds a
 * transaction open so that a concurrent one is sure to overlap it, and a delayed update also holds the row's lock.
 * Close it to drop the trigger.
 */
public final class DatabaseFaults implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final String table;
    private final String name;

    private DatabaseFaults(JdbcTemplate jdbc, String table, String name) {
        this.jdbc = jdbc;
        this.table = table;
        this.name = name;
    }

    /**
     * Fails every INSERT into the table whose column holds the value.
     */
    public static DatabaseFaults failInsert(JdbcTemplate jdbc, String table, String column, String value) {
        return fail(jdbc, "INSERT", table, column, value);
    }

    /**
     * Fails every UPDATE of the table's rows whose column holds the value.
     */
    public static DatabaseFaults failUpdate(JdbcTemplate jdbc, String table, String column, String value) {
        return fail(jdbc, "UPDATE", table, column, value);
    }

    /**
     * Holds every INSERT into the table whose column holds the value for the given number of seconds, inside the
     * inserting transaction.
     */
    public static DatabaseFaults delayInsert(
            JdbcTemplate jdbc,
            String table,
            String column,
            String value,
            double seconds
    ) {
        return install(jdbc, "INSERT", table, column, value, "PERFORM pg_sleep(%s);".formatted(seconds));
    }

    /**
     * Holds every UPDATE of the table's rows whose column holds the value for the given number of seconds, inside the
     * updating transaction and after the row is locked, so a concurrent update of the row waits for it to commit.
     */
    public static DatabaseFaults delayUpdate(
            JdbcTemplate jdbc,
            String table,
            String column,
            String value,
            double seconds
    ) {
        return install(jdbc, "UPDATE", table, column, value, "PERFORM pg_sleep(%s);".formatted(seconds));
    }

    private static DatabaseFaults fail(JdbcTemplate jdbc, String operation, String table, String column, String value) {
        return install(
                jdbc,
                operation,
                table,
                column,
                value,
                "RAISE EXCEPTION 'integration test fault on %s';".formatted(table)
        );
    }

    private static DatabaseFaults install(
            JdbcTemplate jdbc,
            String operation,
            String table,
            String column,
            String value,
            String action
    ) {
        String name = "test_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("""
                CREATE FUNCTION %1$s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.%2$s = '%3$s' THEN
                        %4$s
                    END IF;
                    RETURN NEW;
                END
                $$""".formatted(name, column, value.replace("'", "''"), action));
        jdbc.execute("CREATE TRIGGER %1$s BEFORE %2$s ON %3$s FOR EACH ROW EXECUTE FUNCTION %1$s()"
                .formatted(name, operation, table));
        return new DatabaseFaults(jdbc, table, name);
    }

    @Override
    public void close() {
        jdbc.execute("DROP TRIGGER IF EXISTS %s ON %s".formatted(name, table));
        jdbc.execute("DROP FUNCTION IF EXISTS %s()".formatted(name));
    }
}
