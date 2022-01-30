package com.example.testing.wordpress;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.GroupIdentifier;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DeleteHandler extends BaseHandler<CallbackContext> {
    private static final String SUPPORTED_REGION = "us-west-2";
    private static final String DELETED_INSTANCE_STATE = "terminated";
    private static final int NUMBER_OF_STATE_POLL_RETRIES = 60;
    private static final int POLL_RETRY_DELAY_IN_MS = 5000;
    private static final String TIMED_OUT_MESSAGE = "Timed out waiting for instance to terminate.";
    private AmazonWebServicesClientProxy clientProxy;
    private AmazonEC2 ec2Client;

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {
        final ResourceModel model = request.getDesiredResourceState();

        clientProxy = proxy;
        ec2Client = AmazonEC2ClientBuilder.standard().withRegion(SUPPORTED_REGION).build();
        final CallbackContext currentContext = callbackContext == null ?
                CallbackContext.builder().stabilizationRetriesRemaining(NUMBER_OF_STATE_POLL_RETRIES).build() :
                callbackContext;

        // This Lambda will continually be re-invoked with the current state of the instance, finally succeeding when state stabilizes.
        return deleteInstanceAndUpdateProgress(model, currentContext);
    }

    private ProgressEvent<ResourceModel, CallbackContext> deleteInstanceAndUpdateProgress(ResourceModel model, CallbackContext callbackContext) {

        if (callbackContext.getStabilizationRetriesRemaining() == 0) {
            throw new RuntimeException(TIMED_OUT_MESSAGE);
        }

        if (callbackContext.getInstanceSecurityGroups() == null) {
            final Instance currentInstanceState = currentInstanceState(model.getInstanceId());

            if (DELETED_INSTANCE_STATE.equals(currentInstanceState.getState().getName())) {
                return ProgressEvent.<ResourceModel, CallbackContext>builder()
                        .status(OperationStatus.FAILED)
                        .errorCode(HandlerErrorCode.NotFound)
                        .build();
            }

            final List<String> instanceSecurityGroups = currentInstanceState
                    .getSecurityGroups()
                    .stream()
                    .map(GroupIdentifier::getGroupId)
                    .collect(Collectors.toList());

            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.IN_PROGRESS)
                    .callbackContext(CallbackContext.builder()
                            .stabilizationRetriesRemaining(NUMBER_OF_STATE_POLL_RETRIES)
                            .instanceSecurityGroups(instanceSecurityGroups)
                            .build())
                    .build();
        }

        if (callbackContext.getInstance() == null) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.IN_PROGRESS)
                    .callbackContext(CallbackContext.builder()
                            .instance(deleteInstance(model.getInstanceId()))
                            .instanceSecurityGroups(callbackContext.getInstanceSecurityGroups())
                            .stabilizationRetriesRemaining(NUMBER_OF_STATE_POLL_RETRIES)
                            .build())
                    .build();
        } else if (callbackContext.getInstance().getState().getName().equals(DELETED_INSTANCE_STATE)) {
            callbackContext.getInstanceSecurityGroups().forEach(this::deleteSecurityGroup);
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.SUCCESS)
                    .build();
        } else {
            try {
                Thread.sleep(POLL_RETRY_DELAY_IN_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.IN_PROGRESS)
                    .callbackContext(CallbackContext.builder()
                            .instance(currentInstanceState(model.getInstanceId()))
                            .instanceSecurityGroups(callbackContext.getInstanceSecurityGroups())
                            .stabilizationRetriesRemaining(callbackContext.getStabilizationRetriesRemaining() - 1)
                            .build())
                    .build();
        }

    }

    private Instance deleteInstance(String instanceId) {
        final TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest().withInstanceIds(instanceId);
        return clientProxy.injectCredentialsAndInvoke(terminateInstancesRequest, ec2Client::terminateInstances)
                .getTerminatingInstances()
                .stream()
                .map(instance -> new Instance().withState(instance.getCurrentState()).withInstanceId(instance.getInstanceId()))
                .findFirst()
                .orElse(new Instance());
    }

    private Instance currentInstanceState(String instanceId) {
        DescribeInstancesRequest describeInstancesRequest;
        DescribeInstancesResult describeInstancesResult;

        describeInstancesRequest = new DescribeInstancesRequest().withInstanceIds(instanceId);
        describeInstancesResult = clientProxy.injectCredentialsAndInvoke(describeInstancesRequest, new Function<DescribeInstancesRequest, DescribeInstancesResult>() {
            @Override
            public DescribeInstancesResult apply(DescribeInstancesRequest describeInstancesRequest) {
                return ec2Client.describeInstances(describeInstancesRequest);
            }
        });
        return describeInstancesResult.getReservations()
                .stream()
                .map(Reservation::getInstances)
                .flatMap(List::stream)
                .findFirst()
                .orElse(new Instance());
    }

    private void deleteSecurityGroup(String securityGroupId) {
        final DeleteSecurityGroupRequest deleteSecurityGroupRequest = new DeleteSecurityGroupRequest().withGroupId(securityGroupId);
        clientProxy.injectCredentialsAndInvoke(deleteSecurityGroupRequest, ec2Client::deleteSecurityGroup);
    }
}
