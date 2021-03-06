/*
 * Copyright (c) 2020, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ballerina.c2c.test;

import io.ballerina.c2c.diagnostics.ProjectServiceInfo;
import io.ballerina.c2c.diagnostics.ServiceInfo;
import io.ballerina.c2c.diagnostics.TomlDiagnosticChecker;
import io.ballerina.c2c.utils.TomlHelper;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.toml.api.Toml;
import io.ballerina.toml.validator.TomlValidator;
import io.ballerina.toml.validator.schema.Schema;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import org.apache.commons.io.IOUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;

/**
 * Responsible for code base testing of single file projects.
 *
 * @since 2.0.0
 */
public class CustomDiagnosticsTest {

    @Test
    public void testValidProject() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "valid");

        BuildProject project = BuildProject.load(projectPath);
        Toml toml = TomlHelper
                .createK8sTomlFromProject(project.currentPackage().cloudToml().orElseThrow().tomlDocument());
        TomlValidator validator = new TomlValidator(Schema.from(getValidationSchema()));
        validator.validate(toml);
        List<Diagnostic> diagnostics = toml.diagnostics();
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        Assert.assertEquals(diagnostics.size(), 0);
    }

    @Test
    public void testValidMultifileProject() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "valid-multi-files");

        BuildProject project = BuildProject.load(projectPath);
        Toml toml = TomlHelper
                .createK8sTomlFromProject(project.currentPackage().cloudToml().orElseThrow().tomlDocument());
        TomlValidator validator = new TomlValidator(Schema.from(getValidationSchema()));
        validator.validate(toml);
        List<Diagnostic> diagnostics = toml.diagnostics();
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        Assert.assertEquals(diagnostics.size(), 0);
    }

    @Test
    public void testInvalidInput() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "invalid-input");

        BuildProject project = BuildProject.load(projectPath);
        Toml toml = TomlHelper
                .createK8sTomlFromProject(project.currentPackage().cloudToml().orElseThrow().tomlDocument());
        TomlValidator validator = new TomlValidator(Schema.from(getValidationSchema()));
        validator.validate(toml);
        List<Diagnostic> diagnostics = toml.diagnostics();
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        Assert.assertEquals(diagnostics.size(), 2);
        Assert.assertEquals(diagnostics.get(0).message(),
                "value for key 'min_cpu' expected to match the regex: ^([+-]?[0-9.]+)([eEinumkKMGTP]*[-+]?[0-9]*)$");
        Assert.assertEquals(diagnostics.get(1).message(),
                "value for key 'max_cpu' expected to match the regex: ^([+-]?[0-9.]+)([eEinumkKMGTP]*[-+]?[0-9]*)$");
    }

    @Test
    public void testInvalidSyntax() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "invalid-syntax");

        BuildProject project = BuildProject.load(projectPath);
        Toml toml = TomlHelper
                .createK8sTomlFromProject(project.currentPackage().cloudToml().orElseThrow().tomlDocument());
        TomlValidator validator = new TomlValidator(Schema.from(getValidationSchema()));
        validator.validate(toml);
        List<Diagnostic> diagnostics = toml.diagnostics();
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        Assert.assertEquals(diagnostics.size(), 1);
        Assert.assertEquals(diagnostics.get(0).message(), "missing equal token");
    }

    @Test
    public void testMissingPort() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "missing-port");

        BuildProject project = BuildProject.load(projectPath);
        Toml toml = TomlHelper
                .createK8sTomlFromProject(project.currentPackage().cloudToml().orElseThrow().tomlDocument());
        List<Diagnostic> diagnostics = toml.diagnostics();
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        Assert.assertEquals(diagnostics.size(), 2);
        Assert.assertEquals(diagnostics.get(0).message(), "Invalid Liveness Probe Port");
        Assert.assertEquals(diagnostics.get(1).message(), "Invalid Liveness Probe Path");
    }

    @Test
    public void testInvalidServicePath() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "invalid-service-path");

        BuildProject project = BuildProject.load(projectPath);
        Toml toml = TomlHelper
                .createK8sTomlFromProject(project.currentPackage().cloudToml().orElseThrow().tomlDocument());
        List<Diagnostic> diagnostics = toml.diagnostics();
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        Assert.assertEquals(diagnostics.size(), 1);
        Assert.assertEquals(diagnostics.get(0).message(), "Invalid Liveness Probe Service Path");
    }

    @Test
    public void testInvalidResourcePath() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "invalid-res-path");
        BuildProject project = BuildProject.load(projectPath);
        Toml toml = TomlHelper
                .createK8sTomlFromProject(project.currentPackage().cloudToml().orElseThrow().tomlDocument());
        List<Diagnostic> diagnostics = toml.diagnostics();
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        Assert.assertEquals(diagnostics.size(), 1);
        Assert.assertEquals(diagnostics.get(0).message(), "Invalid Liveness Probe Resource Path");
    }

    @Test
    public void testDefaultConfigValueError() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "default-config-value");
        BuildProject project = BuildProject.load(projectPath);
        List<Diagnostic> diagnostics = new ArrayList<>();
        ProjectServiceInfo projectServiceInfo = new ProjectServiceInfo(project, diagnostics);
        Assert.assertEquals(diagnostics.size(), 1);
        Assert.assertEquals(diagnostics.get(0).message(), "configurables with no default value is not supported");
        Assert.assertEquals(projectServiceInfo.getServiceList().size(), 0);
    }

    @Test
    public void testDefaultConfigValueWarning() {
        Path projectPath = Paths.get("src", "test", "resources", "diagnostics", "configurable-default-port-warning");
        BuildProject project = BuildProject.load(projectPath);
        List<Diagnostic> diagnostics = new ArrayList<>();
        ProjectServiceInfo projectServiceInfo = new ProjectServiceInfo(project, diagnostics);
        Assert.assertEquals(diagnostics.size(), 1);
        Assert.assertEquals(diagnostics.get(0).diagnosticInfo().severity(), DiagnosticSeverity.WARNING);
        Assert.assertEquals(diagnostics.get(0).message(),
                "default value of configurable variable `port` could be overridden in runtime");
        List<ServiceInfo> serviceList = projectServiceInfo.getServiceList();
        Assert.assertEquals(serviceList.size(), 1);
        Assert.assertEquals(serviceList.get(0).getServicePath(), "/helloWorld");
        Assert.assertEquals(serviceList.get(0).getListener().getPort(), 9090);
    }

    private String getValidationSchema() {
        try {
            InputStream inputStream =
                    getClass().getClassLoader().getResourceAsStream("c2c-schema.json");
            if (inputStream == null) {
                throw new MissingResourceException("Schema Not found", "c2c-schema.json", "");
            }
            StringWriter writer = new StringWriter();
            IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8.name());
            return writer.toString();
        } catch (IOException e) {
            throw new MissingResourceException("Schema Not found", "c2c-schema.json", "");
        }
    }
}
