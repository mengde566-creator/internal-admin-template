package com.internaladmin.module.agent.service;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.ScopeMode;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Resolves the current IAM actor only on a request thread; async code receives the immutable result. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AgentActorResolver {
    private final IamActorApi iamActorApi;

    public AgentActorResolver(IamActorApi iamActorApi) {
        this.iamActorApi = iamActorApi;
    }

    public AgentRunContext resolve(Long userId) {
        IamActorDTO actor = iamActorApi.resolve(userId);
        return new AgentRunContext(userId, actor.getDepartmentId(),
                actor.getScopeMode() == ScopeMode.ALL_DEPARTMENTS,
                actor.getAuthorities());
    }
}
