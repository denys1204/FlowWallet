package com.flowwallet.payment.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Makes one write fail inside the service's own transaction, as a lost connection or a full disk would: a trigger
 * raises an error for the rows of one key only, so other tests' writes go through. Close it to drop the trigger.
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

    private static DatabaseFaults fail(JdbcTemplate jdbc, String operation, String table, String column, String value) {
        String name = "test_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("""
                CREATE FUNCTION %1$s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.%2$s = '%3$s' THEN
                        RAISE EXCEPTION 'integration test fault on %4$s';
                    END IF;
                    RETURN NEW;
                END
                $$""".formatted(name, column, value.replace("'", "''"), table));
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
