package com.unicorn.constructs;

import com.unicorn.core.InfrastructureStack;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.codebuild.PipelineProject;
import software.amazon.awscdk.services.codebuild.BuildSpec;
import software.amazon.awscdk.services.codebuild.ComputeType;
import software.amazon.awscdk.services.codebuild.LinuxBuildImage;
import software.amazon.awscdk.services.codebuild.BuildEnvironment;
import software.amazon.awscdk.services.codebuild.BuildEnvironmentVariable;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.StageProps;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitTrigger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class UnicornStoreCI extends Construct {

    public UnicornStoreCI(final Construct scope, final String id,
            InfrastructureStack infrastructureStack, final String projectName) {
        super(scope, id);

        software.amazon.awscdk.services.codecommit.Repository codeCommit =
                software.amazon.awscdk.services.codecommit.Repository.Builder
                        .create(scope, projectName + "-codecommt").repositoryName(projectName)
                        .build();
        codeCommit.applyRemovalPolicy(RemovalPolicy.DESTROY);

        new CfnOutput(scope, "UnicornStoreCodeCommitURL",
                CfnOutputProps.builder().value(codeCommit.getRepositoryCloneUrlHttp()).build());
        new CfnOutput(scope, "arnUnicornStoreCodeCommit",
                CfnOutputProps.builder().value(codeCommit.getRepositoryArn()).build());

        Repository ecr =
                Repository.Builder.create(scope, projectName + "-ecr").repositoryName(projectName)
                        .imageScanOnPush(false).removalPolicy(RemovalPolicy.DESTROY).build();
        new CfnOutput(scope, "UnicornStoreEcrRepositoryURL",
                CfnOutputProps.builder().value(ecr.getRepositoryUri()).build());
        new CfnOutput(scope, "UnicornStoreEcrRepositoryName",
                CfnOutputProps.builder().value(ecr.getRepositoryName()).build());
        new CfnOutput(scope, "arnUnicornStoreEcr",
                CfnOutputProps.builder().value(ecr.getRepositoryArn()).build());
        final String ecrUri = ecr.getRepositoryUri().split("/")[0];
        final String imageName = ecr.getRepositoryUri().split("/")[1];

        // Alternative approaches for multi-architecture Docker images
        // https://aws.amazon.com/blogs/devops/creating-multi-architecture-docker-images-to-support-graviton2-using-aws-codebuild-and-aws-codepipeline/
        // https://github.com/aws-samples/aws-multiarch-container-build-pipeline
        PipelineProject codeBuild =
                PipelineProject.Builder.create(scope, projectName + "-codebuild-build-ecr")
                        .projectName(projectName + "-build-ecr")
                        .buildSpec(BuildSpec.fromSourceFilename("buildspec.yml"))
                        .vpc(infrastructureStack.getVpc())
                        .environment(BuildEnvironment
                                .builder().privileged(true).computeType(ComputeType.SMALL)
                                .buildImage(LinuxBuildImage.AMAZON_LINUX_2_4).build())
                        .environmentVariables(Map.of("ECR_URI",
                                BuildEnvironmentVariable.builder().value(ecrUri).build(),
                                "IMAGE_NAME",
                                BuildEnvironmentVariable.builder().value(imageName).build(),
                                "AWS_DEFAULT_REGION",
                                BuildEnvironmentVariable.builder()
                                        .value(infrastructureStack.getRegion()).build()))
                        .timeout(Duration.minutes(60)).build();

        ecr.grantPullPush(codeBuild);

        Artifact sourceOuput = Artifact.artifact(projectName + "-codecommit-artifact");

        Pipeline.Builder.create(scope, projectName + "-pipeline-build-ecr")
                .pipelineName(projectName + "-build-ecr").crossAccountKeys(false)
                .stages(List.of(
                        StageProps.builder().stageName("source")
                                .actions(List.of(CodeCommitSourceAction.Builder.create()
                                        .actionName("source-codecommit").repository(codeCommit)
                                        .output(sourceOuput).branch("main")
                                        .trigger(CodeCommitTrigger.POLL).runOrder(1).build()))
                                .build(),
                        StageProps.builder().stageName("build")
                                .actions(List.of(CodeBuildAction.Builder.create()
                                        .actionName("build-docker-image").input(sourceOuput)
                                        .project(codeBuild).runOrder(1).build()))
                                .build()))
                .build();
    }
}
