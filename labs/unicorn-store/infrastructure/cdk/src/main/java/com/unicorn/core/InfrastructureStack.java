package com.unicorn.core;

import com.unicorn.constructs.DatabaseSetupConstruct;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.ssm.*;
import software.amazon.awscdk.services.secretsmanager.*;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

import java.util.List;

public class InfrastructureStack extends Stack {

    private final DatabaseSecret databaseSecret;
    private final Secret secretPassword;
    private final StringParameter paramJdbc;
    private final DatabaseInstance database;
    private final EventBus eventBridge;
    private final IVpc vpc;
    private final ISecurityGroup applicationSecurityGroup;


    public InfrastructureStack(final Construct scope, final String id, final StackProps props,
            final VpcStack vpcStack) {
        super(scope, id, props);

        vpc = vpcStack.getVpc();
        new CfnOutput(this, "idUnicornStoreVPC", CfnOutputProps.builder()
                .value(vpc.getVpcId())
                .build());
        Tags.of(vpc).add("unicorn", "true");
        new CfnOutput(this, "arnUnicornStoreVPC", CfnOutputProps.builder()
                .value(vpc.getVpcArn())
                .exportName("arnUnicornStoreVPC")
                .build());
        databaseSecret = createDatabaseSecret();
        new CfnOutput(this, "arnUnicornStoreDbSecret", CfnOutputProps.builder()
                .value(databaseSecret.getSecretFullArn())
                .exportName("arnUnicornStoreDbSecret")
                .build());
        secretPassword = Secret.Builder.create(this, "dbSecretPassword")
            .secretName("unicornstore-db-secret-password")
            .secretStringValue(SecretValue.secretsManager(databaseSecret.getSecretName(), SecretsManagerSecretOptions.builder().jsonField("password").build()))
            .build();
        new CfnOutput(this, "arnUnicornStoreDbSecretPassword", CfnOutputProps.builder()
                .value(secretPassword.getSecretFullArn())
                .exportName("arnUnicornStoreDbSecretPassword")
                .build());
        database = createRDSPostgresInstance(vpc, databaseSecret);
        new CfnOutput(this, "arnUnicornStoreDbInstance", CfnOutputProps.builder()
            .value(database.getInstanceArn())
            .exportName("arnUnicornStoreDbInstance")
            .build());
        new CfnOutput(this, "databaseJDBCConnectionString", CfnOutputProps.builder()
            .value(getDatabaseJDBCConnectionString())
            .exportName("databaseJDBCConnectionString")
            .build());
        paramJdbc = StringParameter.Builder.create(this, "SsmParameterDatabaseJDBCConnectionString")
            .allowedPattern(".*")
            .description("databaseJDBCConnectionString")
            .parameterName("databaseJDBCConnectionString")
            .stringValue(getDatabaseJDBCConnectionString())
            .tier(ParameterTier.STANDARD)
            .build();
        new CfnOutput(this, "arnSsmParameterDatabaseJDBCConnectionString", CfnOutputProps.builder()
            .value(paramJdbc.getParameterArn())
            .exportName("arnSsmParameterDatabaseJDBCConnectionString")
            .build());
        new CfnOutput(this, "ssmParameterDatabaseJDBCConnectionString", CfnOutputProps.builder()
            .value(paramJdbc.getParameterName())
            .exportName("ssmParameterDatabaseJDBCConnectionString")
            .build());
        eventBridge = createEventBus();
        new CfnOutput(this, "arnUnicornStoreEventBus", CfnOutputProps.builder()
            .value(eventBridge.getEventBusArn())
            .exportName("arnUnicornStoreEventBus")
            .build());
        applicationSecurityGroup = new SecurityGroup(this, "ApplicationSecurityGroup",
                SecurityGroupProps
                        .builder()
                        .securityGroupName("applicationSG")
                        .vpc(vpc)
                        .allowAllOutbound(true)
                        .build());

        new DatabaseSetupConstruct(this, "UnicornDatabaseConstruct");

        Role unicornStoreApprunnerRole = Role.Builder.create(this, "unicornstore-apprunner-role")
            .roleName("unicornstore-apprunner-role")
            .assumedBy(new ServicePrincipal("tasks.apprunner.amazonaws.com")).build();

        Role unicornStoreEscRole = Role.Builder.create(this, "unicornstore-ecs-role")
            .roleName("unicornstore-ecs-role")
            .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com")).build();

        getEventBridge().grantPutEventsTo(unicornStoreApprunnerRole);
        getEventBridge().grantPutEventsTo(unicornStoreEscRole);
        getSecretPassword().grantRead(unicornStoreApprunnerRole);
        getSecretPassword().grantRead(unicornStoreEscRole);
        getParamJdbsc().grantRead(unicornStoreApprunnerRole);
        getParamJdbsc().grantRead(unicornStoreEscRole);
    }

    private EventBus createEventBus() {
        return EventBus.Builder.create(this, "UnicornEventBus")
                .eventBusName("unicorns")
                .build();
    }

    private SecurityGroup createDatabaseSecurityGroup(IVpc vpc) {
        var databaseSecurityGroup = SecurityGroup.Builder.create(this, "DatabaseSG")
                .securityGroupName("DatabaseSG")
                .allowAllOutbound(false)
                .vpc(vpc)
                .build();

        databaseSecurityGroup.addIngressRule(
                Peer.ipv4("10.0.0.0/16"),
                Port.tcp(5432),
                "Allow Database Traffic from local network");

        return databaseSecurityGroup;
    }

    private DatabaseInstance createRDSPostgresInstance(IVpc vpc, DatabaseSecret databaseSecret) {

        var databaseSecurityGroup = createDatabaseSecurityGroup(vpc);
        var engine = DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder().version(PostgresEngineVersion.VER_13_4).build());

        return DatabaseInstance.Builder.create(this, "UnicornInstance")
                .engine(engine)
                .vpc(vpc)
                .allowMajorVersionUpgrade(true)
                .backupRetention(Duration.days(0))
                .databaseName("unicorns")
                .instanceIdentifier("UnicornInstance")
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MEDIUM))
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                        .build())
                .securityGroups(List.of(databaseSecurityGroup))
                .credentials(Credentials.fromSecret(databaseSecret))
                .build();
    }

    private DatabaseSecret createDatabaseSecret() {
        return DatabaseSecret.Builder
                .create(this, "postgres")
                .secretName("unicornstore-db-secret")
                .username("postgres").build();
    }

    public EventBus getEventBridge() {
        return eventBridge;
    }

    public IVpc getVpc() {
        return vpc;
    }

    public ISecurityGroup getApplicationSecurityGroup() {
        return applicationSecurityGroup;
    }

    public String getDatabaseSecretString(){
        return databaseSecret.secretValueFromJson("password").toString();
    }

    public String getDatabaseSecretKey(){
        return "password";
    }

    public DatabaseSecret getDatabaseSecret(){
        return databaseSecret;
    }

    public String getDatabaseSecretName(){
        return databaseSecret.getSecretName();
    }

    public String getDatabaseJDBCConnectionString(){
        return "jdbc:postgresql://" + database.getDbInstanceEndpointAddress() + ":5432/unicorns";
    }

    public Secret getSecretPassword(){
        return secretPassword;
    }

    public StringParameter getParamJdbsc(){
        return paramJdbc;
    }
}
