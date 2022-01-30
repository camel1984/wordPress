package com.example.testing.wordpress;

import com.amazonaws.services.ec2.model.Instance;
import lombok.Builder;
import software.amazon.cloudformation.proxy.StdCallbackContext;

import java.util.List;

@Builder
@lombok.Getter
@lombok.Setter
@lombok.ToString
@lombok.EqualsAndHashCode(callSuper = true)
public class CallbackContext extends StdCallbackContext {
    private Instance instance;
    private Integer stabilizationRetriesRemaining;
    private List<String> instanceSecurityGroups;
}
