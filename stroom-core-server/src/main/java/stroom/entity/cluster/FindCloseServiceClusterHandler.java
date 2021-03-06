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

package stroom.entity.cluster;

import javax.annotation.Resource;

import stroom.entity.shared.FindCloseService;
import stroom.task.server.AbstractTaskHandler;
import stroom.task.server.TaskHandlerBean;
import stroom.util.shared.VoidResult;
import stroom.util.spring.StroomBeanStore;

@TaskHandlerBean(task = FindCloseServiceClusterTask.class)
public class FindCloseServiceClusterHandler extends AbstractTaskHandler<FindCloseServiceClusterTask<?>, VoidResult> {
    @Resource
    private StroomBeanStore stroomBeanStore;

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public VoidResult exec(final FindCloseServiceClusterTask<?> task) {
        if (task == null) {
            throw new RuntimeException("No task supplied");
        }
        if (task.getBeanClass() == null) {
            throw new RuntimeException("No task bean class supplied");
        }

        final Object obj = stroomBeanStore.getBean(task.getBeanClass());
        if (obj == null) {
            throw new RuntimeException("Cannot find bean of class type: " + task.getBeanClass());
        }

        ((FindCloseService) obj).findClose(task.getCriteria());
        return new VoidResult();
    }
}
