package com.example.demo.service;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
@Component
public class WorkspaceMappingListener {
    private final CatalogPreparationService preparation;
    public WorkspaceMappingListener(CatalogPreparationService preparation) { this.preparation=preparation; }
    @TransactionalEventListener public void assigned(WorkspaceMappingAssigned event) { preparation.initial(event.accountId(),event.actorId()); }
}
