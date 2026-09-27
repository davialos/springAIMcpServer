package com.springaimcpservercommon.persistence.support;

import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Thin helpers over a {@link DaiStore} shared by the hand-written stores: typed transaction callbacks (Spring's
 * {@code TransactionOperations.execute} returns a nullable result), paging and schema-qualified native SQL names.
 */
public final class StoreSupport {

    private final DaiStore store;

    /**
     * Creates the helper.
     *
     * @param store the persistence unit
     */
    public StoreSupport(DaiStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Runs work in a read-write transaction (joins an existing one).
     *
     * @param work work using the shared entity manager
     * @param <T>  result type
     * @return the non-null result
     */
    public <T> T write(Function<EntityManager, T> work) {
        return Objects.requireNonNull(store.transactions().execute(status -> work.apply(store.entityManager())),
                "transaction callback returned null");
    }

    /**
     * Runs work without result in a read-write transaction (joins an existing one).
     *
     * @param work work using the shared entity manager
     */
    public void writeVoid(Consumer<EntityManager> work) {
        store.transactions().executeWithoutResult(status -> work.accept(store.entityManager()));
    }

    /**
     * Runs work in a new, independent transaction (REQUIRES_NEW).
     *
     * @param work work using the shared entity manager
     * @param <T>  result type
     * @return the non-null result
     */
    public <T> T writeNew(Function<EntityManager, T> work) {
        return Objects.requireNonNull(store.newTransactions().execute(status -> work.apply(store.entityManager())),
                "transaction callback returned null");
    }

    /**
     * Runs work in a read-only transaction.
     *
     * @param work work using the shared entity manager
     * @param <T>  result type
     * @return the non-null result
     */
    public <T> T read(Function<EntityManager, T> work) {
        return Objects.requireNonNull(store.readOnlyTransactions().execute(status -> work.apply(store.entityManager())),
                "transaction callback returned null");
    }

    /**
     * Fetches one page with one extra row to detect whether more rows exist.
     *
     * @param query ordered query
     * @param page  page request
     * @param <T>   row type
     * @return the slice
     */
    public static <T> Slice<T> slice(TypedQuery<T> query, PageRequest page) {
        List<T> rows = query.setFirstResult(page.offset()).setMaxResults(page.limit() + 1).getResultList();
        return Slice.fromOverfetch(rows, page);
    }

    /**
     * Schema-qualified name of a table or function for native SQL.
     *
     * @param name unqualified {@code dai_*} name
     * @return {@code <schema>.<name>}
     */
    public String qualified(String name) {
        return store.schema() + "." + name;
    }

    /**
     * The underlying unit.
     *
     * @return the store
     */
    public DaiStore store() {
        return store;
    }
}
