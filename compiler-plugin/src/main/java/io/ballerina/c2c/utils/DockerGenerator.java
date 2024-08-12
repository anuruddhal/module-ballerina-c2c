/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.c2c.utils;

import io.ballerina.c2c.DockerGenConstants;
import io.ballerina.c2c.exceptions.DockerGenException;
import io.ballerina.c2c.models.CopyFileModel;
import io.ballerina.c2c.models.DockerModel;
import io.ballerina.cli.utils.DebugUtils;
import io.ballerina.cli.utils.TestUtils;
import io.ballerina.projects.JarResolver;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.test.runtime.util.TesterinaConstants;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.ballerina.c2c.DockerGenConstants.EXECUTABLE_JAR;
import static io.ballerina.c2c.DockerGenConstants.REGISTRY_SEPARATOR;
import static io.ballerina.c2c.DockerGenConstants.TAG_SEPARATOR;
import static io.ballerina.c2c.utils.DockerGenUtils.addConfigTomls;
import static io.ballerina.c2c.utils.DockerGenUtils.copyTestConfigFiles;
import static io.ballerina.c2c.utils.DockerGenUtils.getTestSuiteJsonCopiedDir;
import static io.ballerina.c2c.utils.DockerGenUtils.getWorkDir;
import static io.ballerina.c2c.KubernetesConstants.LINE_SEPARATOR;
import static io.ballerina.c2c.utils.DockerGenUtils.copyFileOrDirectory;
import static io.ballerina.c2c.utils.DockerGenUtils.isBlank;
import static io.ballerina.c2c.utils.DockerGenUtils.printDebug;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_INIT_CLASS_NAME;

/**
 * Generates Docker artifacts from annotations.
 */
public class DockerGenerator {

    protected final DockerModel dockerModel;

    public DockerGenerator(DockerModel dockerModel) {
        String registry = dockerModel.getRegistry();
        String imageName = dockerModel.getName();
        imageName = !isBlank(registry) ? registry + REGISTRY_SEPARATOR + imageName + TAG_SEPARATOR
                + dockerModel.getTag() :
                imageName + TAG_SEPARATOR + dockerModel.getTag();
        dockerModel.setName(imageName);

        this.dockerModel = dockerModel;
    }

    public void createArtifacts(PrintStream outStream, String logAppender, Path jarFilePath, Path outputDir)
            throws DockerGenException {
        try {
            if (!this.dockerModel.isThinJar()) {
                DockerGenUtils.writeToFile(generateDockerfile(), outputDir.resolve("Dockerfile"));
                copyFileOrDirectory(this.dockerModel.getFatJarPath(),
                        outputDir.resolve(this.dockerModel.getFatJarPath().getFileName()));
            } else {
                String dockerContent;
                dockerContent = generateDockerfile();
                copyNativeJars(outputDir);
                DockerGenUtils.writeToFile(dockerContent, outputDir.resolve("Dockerfile"));
                Path jarLocation = outputDir.resolve(DockerGenUtils.extractJarName(jarFilePath) + EXECUTABLE_JAR);
                copyFileOrDirectory(jarFilePath, jarLocation);
            }
            copyExternalFiles(outputDir);
            //check image build is enabled.
            if (this.dockerModel.isBuildImage()) {
                outStream.println("\nBuilding the docker image\n");
                buildImage(outputDir);
                outStream.println();
            }
        } catch (IOException e) {
            throw new DockerGenException("unable to write content to " + outputDir);
        }
    }

    public void createTestArtifacts(PrintStream outStream, String logAppender, Path outputDir)
            throws  DockerGenException {

        try {
            String dockerContent;
            dockerContent = generateTestDockerFile(this.dockerModel.getTestSuiteJsonPath(),
                    this.dockerModel.getJacocoAgentJarPath());
            copyNativeJars(outputDir);
            //copy the test suite json
            copyFileOrDirectory(this.dockerModel.getTestSuiteJsonPath(), outputDir);

            //copy the jacoco agent jar
            copyFileOrDirectory(this.dockerModel.getJacocoAgentJarPath(), outputDir);
            DockerGenUtils.writeToFile(dockerContent, outputDir.resolve("Dockerfile"));

            copyTestConfigFiles(outputDir, this.dockerModel);
            copyExternalFiles(outputDir);

            outStream.println("\nBuilding the docker image\n");
            buildImage(outputDir);
            outStream.println();
        } catch (IOException e) {
            throw new DockerGenException("unable to write content to " + outputDir);
        }
    }

    private void copyExternalFiles(Path outputDir) throws DockerGenException {
        for (CopyFileModel copyFileModel : this.dockerModel.getCopyFiles()) {
            // Copy external files to docker folder
            Path target = outputDir.resolve(Paths.get(copyFileModel.getSource()).getFileName());
            Path sourcePath = Paths.get(copyFileModel.getSource());
            if (!sourcePath.isAbsolute()) {
                sourcePath = sourcePath.toAbsolutePath();
            }
            copyFileOrDirectory(sourcePath, target);
        }
    }

    private void copyNativeJars(Path outputDir) throws DockerGenException {
        for (Path jarPath : this.dockerModel.getDependencyJarPaths()) {
            // Copy jar files
            Path target = outputDir.resolve(jarPath.getFileName());
            Path sourcePath = jarPath;
            if (!sourcePath.isAbsolute()) {
                sourcePath = sourcePath.toAbsolutePath();
            }
            copyFileOrDirectory(sourcePath, target);
        }
    }

    /**
     * Create docker image.
     *
     * @param dockerDir dockerfile directory
     */
    public void buildImage(Path dockerDir) throws DockerGenException {
        // validate docker image name
        DockerImageName.validate(this.dockerModel.getName());

        printDebug("building docker image `" + this.dockerModel.getName() + "` from directory `" + dockerDir + "`.");
        ProcessBuilder pb = new ProcessBuilder("docker", "build", "--no-cache", "--force-rm", "-t",
                this.dockerModel.getName(), dockerDir.toFile().toString());
        pb.inheritIO();

        try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new DockerGenException("docker build failed. refer to the build log");
            }

        } catch (IOException | InterruptedException | RuntimeException e) {
            throw new DockerGenException(getErrorMessage(e.getMessage()));
        }
    }

    private String getErrorMessage(String message) {
        switch (message) {
            case "Cannot run program \"docker\": error=2, No such file or directory":
            case "Cannot run program \"docker\": CreateProcess error=2, The system cannot find the file specified":
                return "command not found: docker";
            default:
                return message;
        }
    }

    /**
     * Generate Dockerfile content according to selected jar type.
     *
     * @return Dockerfile content as a string
     */
    private String generateDockerfile() {
        StringBuilder dockerfileContent = new StringBuilder();
        addInitialDockerContent(dockerfileContent);
        if (this.dockerModel.isThinJar()) {
            // Append Jar copy instructions without observability jar and executable jar
            this.dockerModel.getDependencyJarPaths()
                    .stream()
                    .map(Path::getFileName)
                    .filter(path -> !(path.toString().endsWith("-observability-symbols.jar") ||
                            path.toString().endsWith(dockerModel.getJarFileName())))
                    .collect(Collectors.toCollection(TreeSet::new))
                    .forEach(path -> {
                                dockerfileContent.append("COPY ")
                                        .append(path)
                                        .append(" ").append(getWorkDir())
                                        .append("/jars/ ").append(LINE_SEPARATOR);
                                //TODO: Remove once https://github.com/moby/moby/issues/37965 is fixed.
                                boolean isCiBuild = "true".equals(System.getenv().get("CI_BUILD"));
                                if (isCiBuild) {
                                    dockerfileContent.append("RUN true ").append(LINE_SEPARATOR);
                                }
                            }
                            );
            // Append Jar copy for observability jar and executable jar
            this.dockerModel.getDependencyJarPaths().forEach(path -> {
                        if (path.toString().endsWith("observability-symbols.jar") ||
                                path.toString().endsWith(dockerModel.getJarFileName())) {
                            dockerfileContent.append("COPY ")
                                    .append(path.getFileName())
                                    .append(" ").append(getWorkDir())
                                    .append("/jars/ ").append(LINE_SEPARATOR);
                        }
                    }
                                                            );
        } else {
            dockerfileContent.append("COPY ")
                    .append(this.dockerModel.getFatJarPath().getFileName())
                    .append(" ").append(getWorkDir())
                    .append("/jars/ ").append(LINE_SEPARATOR);
        }
        appendUser(dockerfileContent);
        dockerfileContent.append("WORKDIR ").append(getWorkDir()).append(LINE_SEPARATOR);
        appendCommonCommands(dockerfileContent);
        if (isBlank(this.dockerModel.getEntryPoint())) {
            PackageID packageID = this.dockerModel.getPkgId();
            String mainClass = JarResolver.getQualifiedClassName(packageID.orgName.getValue(), packageID.name.getValue(),
                    packageID.version.getValue(), MODULE_INIT_CLASS_NAME);
            List<String> args = new ArrayList<>();
            args.add("java");
            args.add("-Xdiag");
            if (this.dockerModel.isEnableDebug()) {
                args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" +
                        this.dockerModel.getDebugPort());
            }
            args.add("-cp");
            args.add(this.dockerModel.getJarFileName() + ":jars/*");
            args.add(mainClass);
            dockerfileContent.append(entryPointArgBuilder(args));
        } else {
            dockerfileContent.append(this.dockerModel.getEntryPoint());
        }
        if (!isBlank(this.dockerModel.getCommandArg())) {
            dockerfileContent.append(this.dockerModel.getCommandArg());
        }
        dockerfileContent.append(LINE_SEPARATOR);

        return dockerfileContent.toString();
    }

    private String entryPointArgBuilder(List<String> args) {
        return "ENTRYPOINT " +
                "[" + String.join(",", args.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
    }

    private void addInitialDockerContent(StringBuilder dockerfileContent) {
        dockerfileContent.append("# Auto Generated Dockerfile").append(LINE_SEPARATOR);
        dockerfileContent.append("FROM ").append(this.dockerModel.getBaseImage()).append(LINE_SEPARATOR);
        dockerfileContent.append(LINE_SEPARATOR);
        dockerfileContent.append("LABEL maintainer=\"dev@ballerina.io\"").append(LINE_SEPARATOR);
    }

    private String generateTestDockerFile(Path testSuiteJsonPath, Path jacocoAgentJarPath) throws DockerGenException {
        StringBuilder testDockerFileContent = new StringBuilder();
        addInitialDockerContent(testDockerFileContent);

        //copy the test suite json
        testDockerFileContent.append("COPY ");
        Optional<Path> testSuiteJsonPathOptional = Optional.ofNullable(testSuiteJsonPath.getFileName());
        if (testSuiteJsonPathOptional.isPresent()) {
            testDockerFileContent.append(testSuiteJsonPathOptional.get())
                    .append(" ").append(getTestSuiteJsonCopiedDir()).append("/ ").append(LINE_SEPARATOR);
        } else {
            throw new DockerGenException("Test suite json path is not provided");
        }
        new TreeSet<>(this.dockerModel.getDependencyJarPaths())
                .stream()
                .map(Path::getFileName)
                .forEach(path -> {
                    testDockerFileContent.append("COPY ")
                                    .append(path)
                                    .append(" ").append(getWorkDir())
                                    .append("/jars/ ").append(LINE_SEPARATOR);
                        }
                );

        //copy the jacoco agent jar path
        testDockerFileContent.append("COPY ");
        Optional<Path> jacocoAgentJarPathOptional = Optional.ofNullable(jacocoAgentJarPath.getFileName());
        if (jacocoAgentJarPathOptional.isPresent()) {
            testDockerFileContent.append(jacocoAgentJarPathOptional.get())
                    .append(" ").append(getWorkDir())
                    .append("/jars/ ").append(LINE_SEPARATOR);
        } else {
            throw new DockerGenException("Jacoco agent jar path is not provided");
        }
        Path projectSourceRoot = this.dockerModel.getSourceRoot();
        addConfigTomls(testDockerFileContent, this.dockerModel, Paths.get(getWorkDir()), projectSourceRoot.toString());

        appendUser(testDockerFileContent);
        testDockerFileContent.append("WORKDIR ").append(getWorkDir()).append(LINE_SEPARATOR);
        appendCommonCommands(testDockerFileContent);

        if (!isBlank(this.dockerModel.getEntryPoint())) {
            testDockerFileContent.append(this.dockerModel.getEntryPoint()).append(LINE_SEPARATOR);
        } else {
            addDockerTestEntryPoint(testDockerFileContent);
        }

        if (!isBlank(this.dockerModel.getCommandArg())) {
            testDockerFileContent.append(this.dockerModel.getCommandArg()).append(" ");
        } else {
            addDockerTestCMDArgs(testDockerFileContent);
        }
        testDockerFileContent.append(LINE_SEPARATOR);

        return testDockerFileContent.toString();
    }

    private void addDockerTestCMDArgs(StringBuilder testDockerFileContent) {
        if (this.dockerModel.getTestRunTimeCmdArgs() != null) {
            List<String> testRunTimeCmdArgs = this.dockerModel.getTestRunTimeCmdArgs();
            testDockerFileContent.append(LINE_SEPARATOR);
            testDockerFileContent.append(buildCMDArgs(testRunTimeCmdArgs));
        }
    }

    protected String buildCMDArgs(List<String> args) {
        return "CMD " +
                "[" + String.join(",", args.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
    }

    private void addDockerTestEntryPoint(StringBuilder testDockerFileContent) {
        ArrayList<String> args = new ArrayList<>(TestUtils.getInitialCmdArgs("java", getWorkDir()));

        if (this.dockerModel.isEnableDebug()) {
            args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" +
                    this.dockerModel.getDebugPort());
        } else {
            if (DebugUtils.isInDebugMode()) {
                args.add(DebugUtils.getDebugArgs(System.err));
            }
        }

        if (!isBlank(this.dockerModel.getClassPath())) {
            args.add("-cp");
            args.add(this.dockerModel.getClassPath());
        }
        args.add(TesterinaConstants.TESTERINA_LAUNCHER_CLASS_NAME);
        testDockerFileContent.append(entryPointArgBuilder(args));
    }

    protected void appendUser(StringBuilder dockerfileContent) {
        if (this.dockerModel.getBaseImage().equals(DockerGenConstants.JRE_SLIM_BASE)) {
            dockerfileContent.append("RUN addgroup troupe \\").append(LINE_SEPARATOR);
            dockerfileContent.append("    && adduser -S -s /bin/bash -g 'ballerina' -G troupe -D ballerina \\")
                    .append(LINE_SEPARATOR);
            dockerfileContent.append("    && apk add --update --no-cache bash \\").append(LINE_SEPARATOR);
            if (this.dockerModel.isTest()) {
                // give write permission to ballerina user to write any file related to tests
                dockerfileContent.append("    && mkdir -p /home/ballerina/target \\").append(LINE_SEPARATOR);
                dockerfileContent.append("    && chown -R ballerina:troupe /home/ballerina/target \\")
                        .append(LINE_SEPARATOR);
                dockerfileContent.append("    && chmod -R 777 /home/ballerina/target \\")
                        .append(LINE_SEPARATOR);
            }
            dockerfileContent.append("    && rm -rf /var/cache/apk/*").append(LINE_SEPARATOR);
            dockerfileContent.append(LINE_SEPARATOR);
        }
    }

    protected void appendCommonCommands(StringBuilder dockerfileContent) {
        this.dockerModel.getEnv().forEach((key, value) -> dockerfileContent.append("ENV ").
                append(key).append("=").append(value).append(LINE_SEPARATOR));

        this.dockerModel.getCopyFiles().forEach(file -> {
            // Extract the source filename relative to docker folder.
            String sourceFileName = String.valueOf(Paths.get(file.getSource()).getFileName());
            dockerfileContent.append("COPY ")
                    .append(sourceFileName)
                    .append(" ")
                    .append(file.getTarget())
                    .append(LINE_SEPARATOR);
        });

        dockerfileContent.append(LINE_SEPARATOR);

        if (this.dockerModel.isService() && this.dockerModel.getPorts().size() > 0) {
            dockerfileContent.append("EXPOSE");
            this.dockerModel.getPorts().forEach(port -> dockerfileContent.append(" ").append(port));
        }
        dockerfileContent.append(LINE_SEPARATOR);
        if (this.dockerModel.getBaseImage().equals(DockerGenConstants.JRE_SLIM_BASE)) {
            dockerfileContent.append("USER ballerina").append(LINE_SEPARATOR);
            dockerfileContent.append(LINE_SEPARATOR);
        }
    }
}
