package cn.copper.phase2.initialization;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static cn.copper.phase2.initialization.InitializationModels.*;

@Service
public class InitializationService {
    private final InitializationRepository repository;
    private final String environment;

    public InitializationService(InitializationRepository repository,
                                 @Value("${phase2.environment:development}") String environment) {
        this.repository = repository;
        this.environment = environment;
    }

    public boolean connectionReady() {
        return repository.connectionReady();
    }

    public BootstrapOverview overview() {
        return new BootstrapOverview("铜冶炼二期", environment, repository.databaseOverview(),
                repository.counts(), "INITIALIZATION_ONLY");
    }

}
