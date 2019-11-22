package io.scalecube.config.vault;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.bettercloud.vault.EnvironmentLoader;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultException;
import io.scalecube.config.ConfigProperty;
import io.scalecube.config.ConfigRegistry;
import io.scalecube.config.ConfigRegistrySettings;
import io.scalecube.config.ConfigSourceNotAvailableException;
import io.scalecube.config.StringConfigProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class VaultConfigSourceTest {

  /** the environment variable name for vault secret path */
  private static final String VAULT_SECRETS_PATH = "VAULT_SECRETS_PATH";

  // these 3 are actual values we would like to test with
  private static final String VAULT_SECRETS_PATH1 = "secret/application/tenant1";
  private static final String VAULT_SECRETS_PATH2 = "secret/application/tenant2";
  private static final String VAULT_SECRETS_PATH3 = "secret/application2/tenant3";

  @RegisterExtension
  static final VaultContainerExtension vaultContainerExtension = new VaultContainerExtension();

  @BeforeAll
  static void beforeAll() {
    VaultInstance vaultInstance = vaultContainerExtension.vaultInstance();
    vaultInstance.putSecrets(
        VAULT_SECRETS_PATH1, "top_secret=password1", "db_password=dbpassword1");
    vaultInstance.putSecrets(
        VAULT_SECRETS_PATH2, "top_secret=password2", "db_password=dbpassword2");
    vaultInstance.putSecrets(VAULT_SECRETS_PATH3, "secret=password", "password=dbpassword");
  }

  private EnvironmentLoader baseLoader =
      new MockEnvironmentLoader()
          .put("VAULT_TOKEN", vaultContainerExtension.vaultInstance().rootToken())
          .put("VAULT_ADDR", vaultContainerExtension.vaultInstance().address());
  private EnvironmentLoader loader1 =
      new MockEnvironmentLoader(baseLoader).put(VAULT_SECRETS_PATH, VAULT_SECRETS_PATH1);
  private EnvironmentLoader loader2 =
      new MockEnvironmentLoader(baseLoader).put(VAULT_SECRETS_PATH, VAULT_SECRETS_PATH2);
  private EnvironmentLoader loader3 =
      new MockEnvironmentLoader(baseLoader).put(VAULT_SECRETS_PATH, VAULT_SECRETS_PATH3);

  @Test
  void testFirstTenant() {
    VaultConfigSource vaultConfigSource = VaultConfigSource.builder(loader1).build();
    Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
    ConfigProperty actual = loadConfig.get("top_secret");

    assertThat(actual, notNullValue());
    assertThat(actual.name(), equalTo("top_secret"));
    assertThat(actual.valueAsString(""), equalTo("password1"));
  }

  @Test
  void testSecondTenant() {
    VaultConfigSource vaultConfigSource = VaultConfigSource.builder(loader2).build();
    Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
    ConfigProperty actual = loadConfig.get("top_secret");

    assertThat(actual, notNullValue());
    assertThat(actual.name(), equalTo("top_secret"));
    assertThat(actual.valueAsString(""), equalTo("password2"));
  }

  @Test
  void testMissingProperty() {
    VaultConfigSource vaultConfigSource = VaultConfigSource.builder(loader3).build();
    Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();

    assertThat(loadConfig.size(), not(0));

    ConfigProperty actual = loadConfig.get("top_secret");
    assertThat(actual, nullValue());
  }

  @Test
  void testMissingTenant() {
    EnvironmentLoader loader4 =
        new MockEnvironmentLoader(baseLoader).put(VAULT_SECRETS_PATH, "secrets/unknown/path");
    VaultConfigSource vaultConfigSource = VaultConfigSource.builder(loader4).build();

    assertThrows(ConfigSourceNotAvailableException.class, vaultConfigSource::loadConfig);
  }

  @Test
  void testInvalidAddress() {
    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(
                new MockEnvironmentLoader()
                    .put("VAULT_ADDR", "http://invalid.host.local:8200")
                    .put("VAULT_TOKEN", vaultContainerExtension.vaultInstance().rootToken())
                    .put(VAULT_SECRETS_PATH, VAULT_SECRETS_PATH1))
            .build();

    assertThrows(ConfigSourceNotAvailableException.class, vaultConfigSource::loadConfig);
  }

  @Test
  void testInvalidToken() {
    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(
                new MockEnvironmentLoader(baseLoader)
                    .put("VAULT_TOKEN", "zzzzzz")
                    .put(VAULT_SECRETS_PATH, "secrets/unknown/path"))
            .build();

    assertThrows(ConfigSourceNotAvailableException.class, vaultConfigSource::loadConfig);
  }

  @Test
  void shouldWorkWhenRegistryIsReloadedAndVaultIsRunning() throws InterruptedException {
    VaultInstance vaultInstance = vaultContainerExtension.startNewVaultInstance();
    vaultInstance.putSecrets(
        VAULT_SECRETS_PATH1, "top_secret=password1", "db_password=dbpassword1");
    String address = vaultInstance.address();
    String rootToken = vaultInstance.rootToken();

    ConfigRegistrySettings settings =
        ConfigRegistrySettings.builder()
            .addLastSource(
                "vault",
                VaultConfigSource.builder()
                    .config(vaultConfig -> vaultConfig.address(address).token(rootToken))
                    .secretsPath(VAULT_SECRETS_PATH1)
                    .build())
            .reloadIntervalSec(1)
            .build();
    ConfigRegistry configRegistry = ConfigRegistry.create(settings);
    StringConfigProperty configProperty = configRegistry.stringProperty("top_secret");

    assertThat(configProperty.value().get(), containsString("password1"));

    vaultInstance.putSecrets(VAULT_SECRETS_PATH1, " top_secret=new_password");

    TimeUnit.SECONDS.sleep(2);

    assertThat(configProperty.value().get(), containsString("new_password"));
  }

  @Test
  void shouldWorkWhenRegistryIsReloadedAndVaultIsDown() {
    String PASSWORD_PROPERTY_NAME = "password";
    String PASSWORD_PROPERTY_VALUE = "123456";
    String secret = PASSWORD_PROPERTY_NAME + "=" + PASSWORD_PROPERTY_VALUE;

    VaultInstance vaultInstance = vaultContainerExtension.startNewVaultInstance();
    vaultInstance.putSecrets(VAULT_SECRETS_PATH1, secret);
    String address = vaultInstance.address();
    String rootToken = vaultInstance.rootToken();

    ConfigRegistrySettings settings =
        ConfigRegistrySettings.builder()
            .addLastSource(
                "vault",
                VaultConfigSource.builder()
                    .config(vaultConfig -> vaultConfig.address(address).token(rootToken))
                    .secretsPath(VAULT_SECRETS_PATH1)
                    .build())
            .reloadIntervalSec(1)
            .build();
    ConfigRegistry configRegistry = ConfigRegistry.create(settings);
    StringConfigProperty configProperty = configRegistry.stringProperty(PASSWORD_PROPERTY_NAME);
    configProperty.addValidator(Objects::nonNull);

    vaultInstance.close();
    assertFalse(vaultInstance.container().isRunning());

    try {
      TimeUnit.SECONDS.sleep(2);
    } catch (InterruptedException e) {
      fail(e);
    }

    assertThat(configProperty.value().get(), containsString(PASSWORD_PROPERTY_VALUE));
  }

  @Test
  void testSealed() throws Throwable {
    VaultInstance vaultInstance = vaultContainerExtension.startNewVaultInstance();
    Vault vault = vaultInstance.vault();

    try {
      vault.seal().seal();
      assumeTrue(vault.seal().sealStatus().getSealed(), "vault seal status");

      Map<String, String> clientEnv = new HashMap<>();
      clientEnv.put("VAULT_TOKEN", "ROOT");
      clientEnv.put("VAULT_ADDR", vaultInstance.address());
      clientEnv.put(VAULT_SECRETS_PATH, VAULT_SECRETS_PATH1);

      VaultConfigSource.builder(new MockEnvironmentLoader(clientEnv)).build().loadConfig();
      fail("Negative test failed");
    } catch (ConfigSourceNotAvailableException expectedException) {
      assertThat(expectedException.getCause(), instanceOf(VaultException.class));
      String message = expectedException.getCause().getMessage();
      assertThat(message, containsString("Vault responded with HTTP status code: 503"));
    }
  }

  @Test
  void shouldWorkWhenRegistryIsReloadedAndVaultIsUnSealed() throws InterruptedException {
    VaultInstance sealedVaultInstance = vaultContainerExtension.startNewVaultInstance();
    sealedVaultInstance.putSecrets(
        VAULT_SECRETS_PATH1, "top_secret=password1", "db_password=dbpassword1");
    String address = sealedVaultInstance.address();
    String unsealKey = sealedVaultInstance.unsealKey();
    String rootToken = sealedVaultInstance.rootToken();

    ConfigRegistrySettings settings =
        ConfigRegistrySettings.builder()
            .addLastSource(
                "vault",
                VaultConfigSource.builder()
                    .config(vaultConfig -> vaultConfig.address(address).token(rootToken))
                    .secretsPath(VAULT_SECRETS_PATH1)
                    .build())
            .reloadIntervalSec(1)
            .build();

    ConfigRegistry configRegistry = ConfigRegistry.create(settings);
    StringConfigProperty configProperty = configRegistry.stringProperty("top_secret");

    assertThat(
        "initial value of top_secret", configProperty.value().get(), containsString("password1"));

    Vault vault = sealedVaultInstance.vault();
    Map<String, Object> newValues = new HashMap<>();
    newValues.put(configProperty.name(), "new_password");

    try {
      vault.logical().write(VAULT_SECRETS_PATH1, newValues);
      vault.seal().seal();
      assumeTrue(vault.seal().sealStatus().getSealed(), "vault seal status");
    } catch (VaultException vaultException) {
      fail(vaultException.getMessage());
    }
    TimeUnit.SECONDS.sleep(2);
    assumeFalse(
        configProperty.value().isPresent() && configProperty.value().get().contains("new_password"),
        "new value was unexpectedly set");
    try {
      vault.seal().unseal(unsealKey);
      assumeFalse(vault.seal().sealStatus().getSealed(), "vault seal status");
    } catch (VaultException vaultException) {
      fail(vaultException.getMessage());
    }
    TimeUnit.SECONDS.sleep(2);
    assertThat(configProperty.value().get(), containsString("new_password"));
  }

  @Test
  void testRenewableToken() throws InterruptedException {
    String token =
        vaultContainerExtension
            .vaultInstance()
            .createToken("-period=3s -renewable=true")
            .getAuthClientToken();

    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(loader1)
            .tokenSupplier((environmentLoader, config) -> token)
            .build();

    for (int i = 0; i < 10; i++) {
      Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
      ConfigProperty actual = loadConfig.get("top_secret");

      assertThat(actual, notNullValue());
      assertThat(actual.name(), equalTo("top_secret"));
      assertThat(actual.valueAsString(""), equalTo("password1"));

      TimeUnit.SECONDS.sleep(1);
    }
  }

  @Test
  void testNonrenewableToken() {
    String token =
        vaultContainerExtension
            .vaultInstance()
            .createToken("-period=3s -renewable=false")
            .getAuthClientToken();

    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(loader1)
            .tokenSupplier((environmentLoader, config) -> token)
            .build();

    LongAdder times = new LongAdder();

    assertThrows(
        ConfigSourceNotAvailableException.class,
        () -> {
          for (int i = 0; i < 10; i++) {
            Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
            ConfigProperty actual = loadConfig.get("top_secret");

            assertThat(actual, notNullValue());
            assertThat(actual.name(), equalTo("top_secret"));
            assertThat(actual.valueAsString(""), equalTo("password1"));

            times.increment();

            TimeUnit.SECONDS.sleep(1);
          }
        });

    assumeTrue(times.sum() > 1, "at least once was loaded");
  }

  @Test
  void testRenewableTokenWithExplicitMaxTtl() {
    String token =
        vaultContainerExtension
            .vaultInstance()
            .createToken("-period=3s -renewable=true -explicit-max-ttl=6s")
            .getAuthClientToken();

    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(loader1)
            .tokenSupplier((environmentLoader, config) -> token)
            .build();

    LongAdder times = new LongAdder();

    assertThrows(
        ConfigSourceNotAvailableException.class,
        () -> {
          for (int i = 0; i < 10; i++) {
            Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
            ConfigProperty actual = loadConfig.get("top_secret");

            assertThat(actual, notNullValue());
            assertThat(actual.name(), equalTo("top_secret"));
            assertThat(actual.valueAsString(""), equalTo("password1"));

            times.increment();

            TimeUnit.SECONDS.sleep(1);
          }
        });

    assumeTrue(times.sum() > 5, "at least 5 times was loaded");
  }

  @Test
  void testRenewableTokenWithUseLimit() {
    String token =
        vaultContainerExtension
            .vaultInstance()
            .createToken("-period=3s -renewable=true -use-limit=6")
            .getAuthClientToken();

    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(loader1)
            .tokenSupplier((environmentLoader, config) -> token)
            .build();

    assertThrows(
        ConfigSourceNotAvailableException.class,
        () -> {
          for (int i = 0; i < 10; i++) {
            Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
            ConfigProperty actual = loadConfig.get("top_secret");

            assertThat(actual, notNullValue());
            assertThat(actual.name(), equalTo("top_secret"));
            assertThat(actual.valueAsString(""), equalTo("password1"));

            TimeUnit.SECONDS.sleep(1);
          }
        });
  }

  @Test
  void testTokenSupplierGeneratesNewRenewableTokenWithExplicitMaxTtl() throws Exception {
    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(loader1)
            .tokenSupplier(
                (environmentLoader, config) ->
                    vaultContainerExtension
                        .vaultInstance()
                        .createToken("-period=3s -renewable=true -explicit-max-ttl=6s")
                        .getAuthClientToken())
            .build();

    for (int i = 0; i < 10; i++) {
      Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
      ConfigProperty actual = loadConfig.get("top_secret");

      assertThat(actual, notNullValue());
      assertThat(actual.name(), equalTo("top_secret"));
      assertThat(actual.valueAsString(""), equalTo("password1"));

      TimeUnit.SECONDS.sleep(1);
    }
  }

  @Test
  void testRenewableTokenWhichWillBeRevoked() {
    String token =
        vaultContainerExtension
            .vaultInstance()
            .createToken("-period=5s -renewable=true")
            .getAuthClientToken();

    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(loader1)
            .tokenSupplier((environmentLoader, config) -> token)
            .build();

    assertThrows(
        ConfigSourceNotAvailableException.class,
        () -> {
          for (int i = 0; i < 10; i++) {
            Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
            ConfigProperty actual = loadConfig.get("top_secret");

            assertThat(actual, notNullValue());
            assertThat(actual.name(), equalTo("top_secret"));
            assertThat(actual.valueAsString(""), equalTo("password1"));

            TimeUnit.SECONDS.sleep(1);

            if (i == 0) {
              vaultContainerExtension
                  .vaultInstance()
                  .execInContainer("vault token revoke " + token);
            }
          }
        });
  }

  @Test
  void testTokenSupplierGeneratesNewRenewableTokenWhichWillBeRevoked() throws Exception {
    AtomicReference<String> tokenRef = new AtomicReference<>();
    VaultConfigSource vaultConfigSource =
        VaultConfigSource.builder(loader1)
            .tokenSupplier(
                (environmentLoader, config) -> {
                  String token =
                      vaultContainerExtension
                          .vaultInstance()
                          .createToken("-period=10s -renewable=true")
                          .getAuthClientToken();
                  tokenRef.set(token);
                  return token;
                })
            .build();

    for (int i = 0; i < 10; i++) {
      Map<String, ConfigProperty> loadConfig = vaultConfigSource.loadConfig();
      ConfigProperty actual = loadConfig.get("top_secret");

      assertThat(actual, notNullValue());
      assertThat(actual.name(), equalTo("top_secret"));
      assertThat(actual.valueAsString(""), equalTo("password1"));

      TimeUnit.SECONDS.sleep(1);

      if (i / 2 == 0) {
        vaultContainerExtension
            .vaultInstance()
            .execInContainer("vault token revoke " + tokenRef.get());
      }
    }
  }

  private static class MockEnvironmentLoader extends EnvironmentLoader {
    private final Map<String, String> env;

    MockEnvironmentLoader() {
      this(Collections.emptyMap());
    }

    MockEnvironmentLoader(EnvironmentLoader loader) {
      this(((MockEnvironmentLoader) loader).env);
    }

    MockEnvironmentLoader(Map<String, String> base) {
      this.env = new HashMap<>(base);
    }

    MockEnvironmentLoader put(String key, String value) {
      env.put(key, value);
      return this;
    }

    @Override
    public String loadVariable(String name) {
      return env.get(name);
    }

    @Override
    public String toString() {
      return "MockEnvironmentLoader{" + "env=" + env + '}';
    }
  }
}
