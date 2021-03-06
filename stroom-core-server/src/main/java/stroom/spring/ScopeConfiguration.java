/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.spring;

import java.util.HashMap;
import java.util.Map;

import stroom.util.logging.StroomLogger;
import stroom.util.spring.StroomScope;
import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScopeConfiguration {
    private static final StroomLogger LOGGER = StroomLogger.getLogger(ScopeConfiguration.class);

    public ScopeConfiguration() {
        LOGGER.info("ScopeConfiguration loading...");
    }

    // CustomScopeConfigurers are BeanFactoryPostProcessors. They need to be
    // instantiated early in the container lifecycle.
    // Making them static help avoid certain problems related to this.
    @Bean
    public static CustomScopeConfigurer customScopeConfigurer() {
        CustomScopeConfigurer customScopeConfigurer = new CustomScopeConfigurer();
        Map<String, Object> scopes = new HashMap<>();
        scopes.put(StroomScope.TASK, "stroom.util.task.TaskScope");
        scopes.put(StroomScope.THREAD, "stroom.util.thread.ThreadScope");
        customScopeConfigurer.setScopes(scopes);
        return customScopeConfigurer;
    }
}
