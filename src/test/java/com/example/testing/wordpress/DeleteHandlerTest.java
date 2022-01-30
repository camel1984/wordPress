package com.example.testing.wordpress;

import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.GroupIdentifier;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.InstanceStateChange;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
public class DeleteHandlerTest {
    private static String EXPECTED_TIMEOUT_MESSAGE = "Timed out waiting for instance to terminate.";

    @Mock
    private AmazonWebServicesClientProxy proxy;

    @Mock
    private Logger logger;

    @BeforeEach
    public void setup() {
        proxy = mock(AmazonWebServicesClientProxy.class);
        logger = mock(Logger.class);
    }

    @Test
    public void testSuccessState() {
        final DeleteSecurityGroupResult deleteSecurityGroupResult = new DeleteSecurityGroupResult();
        doReturn(deleteSecurityGroupResult).when(proxy).injectCredentialsAndInvoke(any(DeleteSecurityGroupRequest.class), any(Function.class));

        final DeleteHandler handler = new DeleteHandler();

        final ResourceModel model = ResourceModel.builder().instanceId("i-1234").build();

        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final CallbackContext context = CallbackContext.builder()
                .stabilizationRetriesRemaining(1)
                .instanceSecurityGroups(Arrays.asList("sg-1234"))
                .instance(new Instance().withState(new InstanceState().withName("terminated")))
                .build();

        final ProgressEvent<ResourceModel, CallbackContext> response
                = handler.handleRequest(proxy, request, context, logger);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(response.getCallbackContext()).isNull();
        assertThat(response.getCallbackDelaySeconds()).isEqualTo(0);
        assertThat(response.getResourceModel()).isEqualTo(request.getDesiredResourceState());
        assertThat(response.getResourceModels()).isNull();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getErrorCode()).isNull();
    }

    @Test
    public void testHandlerInvokedWhenInstanceIsAlreadyTerminated() {
        final DescribeInstancesResult describeInstancesResult =
                new DescribeInstancesResult().withReservations(new Reservation().withInstances(new Instance().withState(new InstanceState().withName("terminated"))
                        .withSecurityGroups(new GroupIdentifier().withGroupId("sg-1234"))));
        doReturn(describeInstancesResult).when(proxy).injectCredentialsAndInvoke(any(DescribeInstancesRequest.class), any(Function.class));

        final DeleteHandler handler = new DeleteHandler();

        final ResourceModel model = ResourceModel.builder().instanceId("i-1234").build();

        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final ProgressEvent<ResourceModel, CallbackContext> response
                = handler.handleRequest(proxy, request, null, logger);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getCallbackContext()).isNull();
        assertThat(response.getCallbackDelaySeconds()).isEqualTo(0);
        assertThat(response.getResourceModel()).isNull();
        assertThat(response.getResourceModels()).isNull();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getErrorCode()).isEqualTo(HandlerErrorCode.NotFound);
    }

    @Test
    public void testInProgressStateSecurityGroupsNotGathered() {
        final DescribeInstancesResult describeInstancesResult =
                new DescribeInstancesResult().withReservations(new Reservation().withInstances(new Instance().withState(new InstanceState().withName("running"))
                        .withSecurityGroups(new GroupIdentifier().withGroupId("sg-1234"))));
        doReturn(describeInstancesResult).when(proxy).injectCredentialsAndInvoke(any(DescribeInstancesRequest.class), any(Function.class));

        final DeleteHandler handler = new DeleteHandler();

        final ResourceModel model = ResourceModel.builder().instanceId("i-1234").build();

        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final ProgressEvent<ResourceModel, CallbackContext> response
                = handler.handleRequest(proxy, request, null, logger);

        final CallbackContext desiredOutputContext = CallbackContext.builder()
                .stabilizationRetriesRemaining(60)
                .instanceSecurityGroups(Arrays.asList("sg-1234"))
                .build();
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.IN_PROGRESS);
        assertThat(response.getCallbackContext()).isEqualToComparingFieldByField(desiredOutputContext);
        assertThat(response.getCallbackDelaySeconds()).isEqualTo(0);
        assertThat(response.getResourceModel()).isEqualTo(request.getDesiredResourceState());
        assertThat(response.getResourceModels()).isNull();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getErrorCode()).isNull();
    }

    @Test
    public void testInProgressStateSecurityGroupsGathered() {
        final InstanceState inProgressState = new InstanceState().withName("in-progress");
        final TerminateInstancesResult terminateInstancesResult =
                new TerminateInstancesResult().withTerminatingInstances(new InstanceStateChange().withCurrentState(inProgressState));
        doReturn(terminateInstancesResult).when(proxy).injectCredentialsAndInvoke(any(TerminateInstancesRequest.class), any(Function.class));

        final DeleteHandler handler = new DeleteHandler();

        final ResourceModel model = ResourceModel.builder().instanceId("i-1234").build();

        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final CallbackContext context = CallbackContext.builder()
                .stabilizationRetriesRemaining(60)
                .instanceSecurityGroups(Arrays.asList("sg-1234"))
                .build();

        final ProgressEvent<ResourceModel, CallbackContext> response
                = handler.handleRequest(proxy, request, context, logger);

        final CallbackContext desiredOutputContext = CallbackContext.builder()
                .stabilizationRetriesRemaining(60)
                .instanceSecurityGroups(context.getInstanceSecurityGroups())
                .instance(new Instance().withState(inProgressState))
                .build();

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.IN_PROGRESS);
        assertThat(response.getCallbackContext()).isEqualToComparingFieldByField(desiredOutputContext);
        assertThat(response.getCallbackDelaySeconds()).isEqualTo(0);
        assertThat(response.getResourceModel()).isEqualTo(request.getDesiredResourceState());
        assertThat(response.getResourceModels()).isNull();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getErrorCode()).isNull();
    }

    @Test
    public void testInProgressStateInstanceTerminationInvoked() {
        final InstanceState inProgressState = new InstanceState().withName("in-progress");
        final GroupIdentifier group = new GroupIdentifier().withGroupId("sg-1234");
        final Instance instance = new Instance().withState(inProgressState).withSecurityGroups(group);
        final DescribeInstancesResult describeInstancesResult =
                new DescribeInstancesResult().withReservations(new Reservation().withInstances(instance));
        doReturn(describeInstancesResult).when(proxy).injectCredentialsAndInvoke(any(DescribeInstancesRequest.class), any(Function.class));

        final DeleteHandler handler = new DeleteHandler();

        final ResourceModel model = ResourceModel.builder().instanceId("i-1234").build();

        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final CallbackContext context = CallbackContext.builder()
                .stabilizationRetriesRemaining(60)
                .instance(new Instance().withState(inProgressState).withSecurityGroups(group))
                .instanceSecurityGroups(Arrays.asList("sg-1234"))
                .build();

        final ProgressEvent<ResourceModel, CallbackContext> response
                = handler.handleRequest(proxy, request, context, logger);

        final CallbackContext desiredOutputContext = CallbackContext.builder()
                .stabilizationRetriesRemaining(59)
                .instanceSecurityGroups(context.getInstanceSecurityGroups())
                .instance(new Instance().withState(inProgressState).withSecurityGroups(group))
                .build();

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OperationStatus.IN_PROGRESS);
        assertThat(response.getCallbackContext()).isEqualToComparingFieldByField(desiredOutputContext);
        assertThat(response.getCallbackDelaySeconds()).isEqualTo(0);
        assertThat(response.getResourceModel()).isEqualTo(request.getDesiredResourceState());
        assertThat(response.getResourceModels()).isNull();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getErrorCode()).isNull();
    }

    @Test
    public void testStabilizationTimeout() {
        final DeleteHandler handler = new DeleteHandler();

        final ResourceModel model = ResourceModel.builder().instanceId("i-1234").build();

        final ResourceHandlerRequest<ResourceModel> request = ResourceHandlerRequest.<ResourceModel>builder()
                .desiredResourceState(model)
                .build();

        final CallbackContext context = CallbackContext.builder()
                .stabilizationRetriesRemaining(0)
                .instanceSecurityGroups(Arrays.asList("sg-1234"))
                .instance(new Instance().withState(new InstanceState().withName("terminated")))
                .build();

        try {
            handler.handleRequest(proxy, request, context, logger);
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo(EXPECTED_TIMEOUT_MESSAGE);
        }
    }

}
