package com.springaimcpservercommon.ai.guard;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Hibernate {@link Integrator} that installs pre-insert/update/delete listeners for AI read scope
 * enforcement (ADR-0014, LLD-06 §6).
 *
 * <p>When the current thread carries the {@link AiReadScope#FLAG} ScopedValue, any Hibernate flush
 * that attempts to execute a DML statement throws {@link AiWriteViolationException}, rolling back
 * the transaction before any write reaches the database.
 *
 * <p>Registered via {@code META-INF/services/org.hibernate.integrator.spi.Integrator}.
 * This class is stateless and safe to share across sessions.
 */
public final class AiWriteGuardIntegrator
        implements Integrator, PreInsertEventListener, PreUpdateEventListener, PreDeleteEventListener {

    @Override
    public void integrate(org.hibernate.boot.Metadata metadata, org.hibernate.boot.spi.BootstrapContext bootstrapContext,
                           SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry()
                .requireService(EventListenerRegistry.class);
        registry.appendListeners(EventType.PRE_INSERT, this);
        registry.appendListeners(EventType.PRE_UPDATE, this);
        registry.appendListeners(EventType.PRE_DELETE, this);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                              SessionFactoryServiceRegistry serviceRegistry) {
        // Nothing to clean up — listeners are registered on the event type, not stored elsewhere.
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        checkAiReadScope(event.getEntity(), "INSERT");
        return false;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        checkAiReadScope(event.getEntity(), "UPDATE");
        return false;
    }

    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        checkAiReadScope(event.getEntity(), "DELETE");
        return false;
    }

    private static void checkAiReadScope(Object entity, String operation) {
        if (AiReadScope.isActive()) {
            String entityName = entity == null ? "unknown" : entity.getClass().getSimpleName();
            throw new AiWriteViolationException(entityName, operation);
        }
    }
}
