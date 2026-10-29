package org.gms.soloacceptance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import soloMapling.game.population.RobotPopulationService;

@Configuration
public class RobotPopulationConfiguration {
    @Bean
    public RobotPopulationService robotPopulationService(Environment environment) {
        return new RobotPopulationService(environment);
    }
}
