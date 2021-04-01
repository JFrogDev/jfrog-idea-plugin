package com.jfrog.ide.idea.ui.configuration;

import com.google.common.collect.Lists;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.messages.MessageBus;
import com.jfrog.ide.idea.configuration.GlobalSettings;
import com.jfrog.ide.idea.configuration.ServerConfigImpl;
import com.jfrog.ide.idea.events.ApplicationEvents;
import com.jfrog.ide.idea.log.Logger;
import com.jfrog.ide.idea.ui.components.ConnectionResultsGesture;
import com.jfrog.xray.client.Xray;
import com.jfrog.xray.client.impl.XrayClientBuilder;
import com.jfrog.xray.client.services.system.Version;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryDependenciesClientBuilder;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryDependenciesClient;

import javax.swing.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static com.jfrog.ide.common.ci.Utils.createAqlForBuildArtifacts;
import static com.jfrog.ide.common.utils.XrayConnectionUtils.*;
import static com.jfrog.ide.idea.ui.configuration.ExclusionsVerifier.DEFAULT_EXCLUSIONS;
import static com.jfrog.ide.idea.ui.configuration.Utils.clearText;
import static org.apache.commons.collections4.CollectionUtils.addIgnoreNull;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * Created by romang on 1/29/17.
 */
public class JFrogGlobalConfiguration implements Configurable, Configurable.NoScroll {

    public static final String USER_AGENT = "jfrog-idea-plugin/" + JFrogGlobalConfiguration.class.getPackage().getImplementationVersion();

    private ServerConfigImpl serverConfig;
    private JButton testConnectionButton;
    private JBPasswordField password;
    private JLabel connectionResults;
    private JBTextField excludedPaths;
    private JBTextField username;
    private JBTextField platformUrl;
    private JPanel config;
    private JBCheckBox connectionDetailsFromEnv;
    private ConnectionRetriesSpinner connectionRetries;
    private ConnectionTimeoutSpinner connectionTimeout;

    private JBTextField xrayUrl;
    private JBTextField artifactoryUrl;
    private JCheckBox setRtAndXraySeparately;
    private JLabel xrayConnectionResults;
    private JLabel artifactoryConnectionResults;
    private ConnectionResultsGesture connectionResultsGesture;
    private ConnectionResultsGesture artifactoryConnectionResultsGesture;

    public JFrogGlobalConfiguration() {
        initUrls();
        initTestConnection();
        initConnectionDetailsFromEnv();
    }

    private void initUrls() {
        setRtAndXraySeparately.addActionListener(listener -> {
            boolean selected = ((AbstractButton) listener.getSource()).isSelected();
            xrayUrl.setEnabled(selected);
            artifactoryUrl.setEnabled(selected);
            if (!selected) {
                resolveXrayAndArtifactoryUrls();
            }
        });
        platformUrl.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!setRtAndXraySeparately.isSelected()) {
                    resolveXrayAndArtifactoryUrls();
                }
            }
        });
        xrayUrl.setEnabled(false);
        artifactoryUrl.setEnabled(false);
    }

    private void initTestConnection() {
        connectionResultsGesture = new ConnectionResultsGesture(xrayConnectionResults);
        artifactoryConnectionResultsGesture = new ConnectionResultsGesture(artifactoryConnectionResults);
        testConnectionButton.addActionListener(e -> ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!setRtAndXraySeparately.isSelected()) {
                resolveXrayAndArtifactoryUrls();
            }
            List<String> results = new ArrayList<>();
            addIgnoreNull(results, checkXrayConnection());
            addIgnoreNull(results, checkArtifactoryConnection());
            setConnectionResults(String.join("<br/>", results));
        }));
    }

    private void resolveXrayAndArtifactoryUrls() {
        String platformUrlStr = platformUrl.getText();
        if (isBlank(platformUrlStr)) {
            if (!setRtAndXraySeparately.isSelected()) {
                clearText(xrayUrl, artifactoryUrl);
            }
            return;
        }
        platformUrlStr = removeEnd(platformUrlStr, "/");
        xrayUrl.setText(platformUrlStr + "/xray");
        artifactoryUrl.setText(platformUrlStr + "/artifactory");
    }

    private String checkXrayConnection() {
        if (isBlank(xrayUrl.getText())) {
            return null;
        }
        try {
            Xray xrayClient = createXrayClient();

            setConnectionResults("Connecting to Xray...");
            config.validate();
            config.repaint();
            Version xrayVersion = xrayClient.system().version();

            // Check version
            if (!isXrayVersionSupported(xrayVersion)) {
                connectionResultsGesture.setFailure(Results.unsupported(xrayVersion));
                return Results.unsupported(xrayVersion);
            }

            // Check permissions
            Pair<Boolean, String> testComponentPermissionRes = testComponentPermission(xrayClient);
            if (!testComponentPermissionRes.getLeft()) {
                throw new IOException(testComponentPermissionRes.getRight());
            }

            if (!isXrayVersionForCiSupported(xrayVersion)) {
                connectionResultsGesture.setFailure(Results.unsupportedForCi(xrayVersion));
                return Results.unsupportedForCi(xrayVersion);
            }

            connectionResultsGesture.setSuccess();
            return Results.success(xrayVersion);
        } catch (IOException exception) {
            connectionResultsGesture.setFailure(ExceptionUtils.getRootCauseMessage(exception));
            return "Could not connect to JFrog Xray.";
        }
    }

    private String checkArtifactoryConnection() {
        if (StringUtils.isBlank(artifactoryUrl.getText())) {
            return null;
        }
        try (ArtifactoryDependenciesClient artifactoryClient = createArtifactoryDependenciesClient().build()) {
            setConnectionResults("Connecting to Artifactory...");
            config.validate();
            config.repaint();

            // Check connection.
            // This command will throw an exception if there is a connection or credentials issue.
            artifactoryClient.searchArtifactsByAql(createAqlForBuildArtifacts("*"));

            artifactoryConnectionResultsGesture.setSuccess();
            return "Successfully connected to Artifactory version" + artifactoryClient.getArtifactoryVersion();
        } catch (Exception exception) {
            artifactoryConnectionResultsGesture.setFailure(ExceptionUtils.getRootCauseMessage(exception));
            return "Could not connect to JFrog Artifactory.";
        }
    }

    private void initConnectionDetailsFromEnv() {
        List<JComponent> effectedComponents = Lists.newArrayList(setRtAndXraySeparately, platformUrl, username, password);
        connectionDetailsFromEnv.addItemListener(e -> {
            JBCheckBox cb = (JBCheckBox) e.getSource();
            if (cb.isSelected()) {
                xrayUrl.setEnabled(false);
                artifactoryUrl.setEnabled(false);
                effectedComponents.forEach(field -> field.setEnabled(false));
                serverConfig.readConnectionDetailsFromEnv();
                updateConnectionDetailsTextFields();
            } else {
                effectedComponents.forEach(field -> field.setEnabled(true));
                // Restore connection details if original settings were inserted manually.
                // This will prevent losing data after checking/unchecking the checkbox.
                ServerConfigImpl oldConfig = GlobalSettings.getInstance().getServerConfig();
                if (oldConfig != null && !oldConfig.isConnectionDetailsFromEnv()) {
                    reset();
                }
            }
        });
    }

    private void setConnectionResults(String results) {
        if (results == null) {
            return;
        }
        connectionResults.setText("<html>" + results + "</html>");
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "JFrog Configuration";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return "Setup page for JFrog Xray and Artifactory connection details.";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return config;
    }

    @Override
    public boolean isModified() {
        serverConfig = new ServerConfigImpl.Builder()
                .setUrl(platformUrl.getText())
                .setArtifactoryUrl(artifactoryUrl.getText())
                .setXrayUrl(xrayUrl.getText())
                .setUsername(username.getText())
                .setPassword(String.valueOf(password.getPassword()))
                .setExcludedPaths(excludedPaths.getText())
                .setConnectionDetailsFromEnv(connectionDetailsFromEnv.isSelected())
                .setConnectionRetries(connectionRetries.getNumber())
                .setConnectionTimeout(connectionTimeout.getNumber())
                .build();

        return !serverConfig.equals(GlobalSettings.getInstance().getServerConfig());
    }

    @Override
    public void apply() {
        GlobalSettings globalSettings = GlobalSettings.getInstance();
        globalSettings.updateConfig(serverConfig);
        MessageBus messageBus = ApplicationManager.getApplication().getMessageBus();
        messageBus.syncPublisher(ApplicationEvents.ON_CONFIGURATION_DETAILS_CHANGE).update();
        connectionResults.setText("");
        loadConfig();
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private Xray createXrayClient() {
        String urlStr = trim(xrayUrl.getText());
        return (Xray) new XrayClientBuilder()
                .setUrl(urlStr)
                .setUserName(trim(username.getText()))
                .setPassword(String.valueOf(password.getPassword()))
                .setUserAgent(USER_AGENT)
                .setInsecureTls(serverConfig.isInsecureTls())
                .setSslContext(serverConfig.getSslContext())
                .setProxyConfiguration(serverConfig.getProxyConfForTargetUrl(urlStr))
                .setLog(Logger.getInstance())
                .build();
    }

    private ArtifactoryDependenciesClientBuilder createArtifactoryDependenciesClient() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        String urlStr = trim(artifactoryUrl.getText());
        return new ArtifactoryDependenciesClientBuilder()
                .setArtifactoryUrl(urlStr)
                .setUsername(serverConfig.getUsername())
                .setPassword(serverConfig.getPassword())
                .setProxyConfiguration(serverConfig.getProxyConfForTargetUrl(urlStr))
                .setLog(Logger.getInstance())
                .setSslContext(serverConfig.isInsecureTls() ?
                        SSLContextBuilder.create().loadTrustMaterial(TrustAllStrategy.INSTANCE).build() :
                        serverConfig.getSslContext());
    }

    private void loadConfig() {
        platformUrl.getEmptyText().setText("Example: https://acme.jfrog.io");
        xrayUrl.getEmptyText().setText("Example: https://acme.jfrog.io/xray");
        artifactoryUrl.getEmptyText().setText("Example: https://acme.jfrog.io/artifactory");
        excludedPaths.setInputVerifier(new ExclusionsVerifier(excludedPaths));
        connectionResults.setText("");

        serverConfig = GlobalSettings.getInstance().getServerConfig();
        if (serverConfig != null) {
            updateConnectionDetailsTextFields();
            excludedPaths.setText(serverConfig.getExcludedPaths());
            connectionRetries.setValue(serverConfig.getConnectionRetries());
            connectionTimeout.setValue(serverConfig.getConnectionTimeout());
            connectionDetailsFromEnv.setSelected(serverConfig.isConnectionDetailsFromEnv());
        } else {
            clearText(platformUrl, xrayUrl, artifactoryUrl, username, password);
            excludedPaths.setText(DEFAULT_EXCLUSIONS);
            connectionDetailsFromEnv.setSelected(false);
            connectionRetries.setValue(ConnectionRetriesSpinner.RANGE.initial);
            connectionTimeout.setValue(ConnectionTimeoutSpinner.RANGE.initial);
        }
    }

    private void updateConnectionDetailsTextFields() {
        platformUrl.setText(serverConfig.getUrl());
        xrayUrl.setText(serverConfig.getXrayUrl());
        artifactoryUrl.setText(serverConfig.getArtifactoryUrl());
        username.setText(serverConfig.getUsername());
        password.setText(serverConfig.getPassword());
        if (!isAllBlank(xrayUrl.getText(), artifactoryUrl.getText()) && isBlank(platformUrl.getText())) {
            setRtAndXraySeparately.getModel().setSelected(true);
            setRtAndXraySeparately.getModel().setPressed(true);
            xrayUrl.setEnabled(true);
            artifactoryUrl.setEnabled(true);
        }
    }
}
