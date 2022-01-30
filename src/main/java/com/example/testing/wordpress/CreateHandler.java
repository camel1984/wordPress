package com.example.testing.wordpress;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceNetworkInterfaceSpecification;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.IpRange;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public class CreateHandler extends BaseHandler<CallbackContext> {
    private static final String SUPPORTED_REGION = "us-west-2";
    private static final String WORDPRESS_AMI_ID = "ami-04fb0368671b6f138";
    private static final String INSTANCE_TYPE = "m4.large";
    private static final String SITE_NAME_TAG_KEY = "Name";
    private static final String AVAILABLE_INSTANCE_STATE = "running";
    private static final int NUMBER_OF_STATE_POLL_RETRIES = 60;
    private static final int POLL_RETRY_DELAY_IN_MS = 5000;
    private static final String TIMED_OUT_MESSAGE = "Timed out waiting for instance to become available.";

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
        return createInstanceAndUpdateProgress(model, currentContext);
    }

    private ProgressEvent<ResourceModel, CallbackContext> createInstanceAndUpdateProgress(ResourceModel model, CallbackContext callbackContext) {
        // This Lambda will continually be re-invoked with the current state of the instance, finally succeeding when state stabilizes.
        final Instance instanceStateSoFar = callbackContext.getInstance();

        if (callbackContext.getStabilizationRetriesRemaining() == 0) {
            throw new RuntimeException(TIMED_OUT_MESSAGE);
        }

        if (instanceStateSoFar == null) {
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                    .resourceModel(model)
                    .status(OperationStatus.IN_PROGRESS)
                    .callbackContext(CallbackContext.builder()
                            .instance(createEC2Instance(model))
                            .stabilizationRetriesRemaining(NUMBER_OF_STATE_POLL_RETRIES)
                            .build())
                    .build();
        } else if (instanceStateSoFar.getState().getName().equals(AVAILABLE_INSTANCE_STATE)) {
            model.setInstanceId(instanceStateSoFar.getInstanceId());
            model.setPublicIp(instanceStateSoFar.getPublicIpAddress());
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
                            .instance(updatedInstanceProgress(instanceStateSoFar.getInstanceId()))
                            .stabilizationRetriesRemaining(callbackContext.getStabilizationRetriesRemaining() - 1)
                            .build())
                    .build();
        }
    }

    private Instance createEC2Instance(ResourceModel model) {
        final String securityGroupId = createSecurityGroupForInstance(model);
        final RunInstancesRequest runInstancesRequest = new RunInstancesRequest()
                .withInstanceType(INSTANCE_TYPE)
                .withImageId(WORDPRESS_AMI_ID)
                .withNetworkInterfaces(new InstanceNetworkInterfaceSpecification()
                        .withAssociatePublicIpAddress(true)
                        .withDeviceIndex(0)
                        .withGroups(securityGroupId)
                        .withSubnetId(model.getSubnetId()))
                .withMaxCount(1)
                .withMinCount(1)
                .withTagSpecifications(buildTagFromSiteName(model.getName()));

        try {
            return clientProxy.injectCredentialsAndInvoke(runInstancesRequest, ec2Client::runInstances)
                    .getReservation()
                    .getInstances()
                    .stream()
                    .findFirst()
                    .orElse(new Instance());
        } catch (Throwable e) {
            attemptToCleanUpSecurityGroup(securityGroupId);
            throw new RuntimeException(e);
        }
    }

    private String createSecurityGroupForInstance(ResourceModel model) {
        String vpcId;
        try {
            vpcId = getVpcIdFromSubnetId(model.getSubnetId());
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }

        final String securityGroupName = model.getName() + "-" + UUID.randomUUID().toString();

        final CreateSecurityGroupRequest createSecurityGroupRequest = new CreateSecurityGroupRequest()
                .withGroupName(securityGroupName)
                .withDescription("Created for the test WordPress blog: " + model.getName())
                .withVpcId(vpcId);

        final String securityGroupId =
                clientProxy.injectCredentialsAndInvoke(createSecurityGroupRequest, ec2Client::createSecurityGroup)
                        .getGroupId();

        final AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest = new AuthorizeSecurityGroupIngressRequest()
                .withGroupId(securityGroupId)
                .withIpPermissions(openHTTP(), openHTTPS());

        clientProxy.injectCredentialsAndInvoke(authorizeSecurityGroupIngressRequest, ec2Client::authorizeSecurityGroupIngress);

        return securityGroupId;
    }

    private String getVpcIdFromSubnetId(String subnetId) throws Throwable {
        final DescribeSubnetsRequest describeSubnetsRequest = new DescribeSubnetsRequest()
                .withSubnetIds(subnetId);

        final DescribeSubnetsResult describeSubnetsResult =
                clientProxy.injectCredentialsAndInvoke(describeSubnetsRequest, new Function<DescribeSubnetsRequest, DescribeSubnetsResult>() {
                    @Override
                    public DescribeSubnetsResult apply(DescribeSubnetsRequest describeSubnetsRequest) {
                        return ec2Client.describeSubnets(describeSubnetsRequest);
                    }
                });

        return describeSubnetsResult.getSubnets()
                .stream()
                .map(Subnet::getVpcId)
                .findFirst()
                .orElseThrow(() -> {
                    throw new RuntimeException("Subnet " + subnetId + " not found");
                });
    }

    private IpPermission openHTTP() {
        return new IpPermission().withIpProtocol("tcp")
                .withFromPort(80)
                .withToPort(80)
                .withIpv4Ranges(new IpRange().withCidrIp("0.0.0.0/0"));
    }

    private IpPermission openHTTPS() {
        return new IpPermission().withIpProtocol("tcp")
                .withFromPort(443)
                .withToPort(443)
                .withIpv4Ranges(new IpRange().withCidrIp("0.0.0.0/0"));
    }

    private TagSpecification buildTagFromSiteName(String siteName) {
        return new TagSpecification()
                .withResourceType("instance")
                .withTags(new Tag().withKey(SITE_NAME_TAG_KEY).withValue(siteName));
    }

    private Instance updatedInstanceProgress(String instanceId) {
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

    private void attemptToCleanUpSecurityGroup(String securityGroupId) {
        final DeleteSecurityGroupRequest deleteSecurityGroupRequest = new DeleteSecurityGroupRequest().withGroupId(securityGroupId);
        clientProxy.injectCredentialsAndInvoke(deleteSecurityGroupRequest, ec2Client::deleteSecurityGroup);
    }
}
