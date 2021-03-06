/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.permissioning;

import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AccountWhitelistController {

  private static final Logger LOG = LogManager.getLogger();

  private static final int ACCOUNT_BYTES_SIZE = 20;
  private LocalPermissioningConfiguration configuration;
  private List<String> accountWhitelist = new ArrayList<>();
  private final WhitelistPersistor whitelistPersistor;

  public AccountWhitelistController(final LocalPermissioningConfiguration configuration) {
    this(
        configuration,
        new WhitelistPersistor(configuration.getAccountPermissioningConfigFilePath()));
  }

  public AccountWhitelistController(
      final LocalPermissioningConfiguration configuration,
      final WhitelistPersistor whitelistPersistor) {
    this.configuration = configuration;
    this.whitelistPersistor = whitelistPersistor;
    readAccountsFromConfig(configuration);
  }

  private void readAccountsFromConfig(final LocalPermissioningConfiguration configuration) {
    if (configuration != null && configuration.isAccountWhitelistEnabled()) {
      if (!configuration.getAccountWhitelist().isEmpty()) {
        addAccounts(configuration.getAccountWhitelist());
      }
    }
  }

  public WhitelistOperationResult addAccounts(final List<String> accounts) {
    final WhitelistOperationResult inputValidationResult = inputValidation(accounts);
    if (inputValidationResult != WhitelistOperationResult.SUCCESS) {
      return inputValidationResult;
    }

    boolean inputHasExistingAccount = accounts.stream().anyMatch(accountWhitelist::contains);
    if (inputHasExistingAccount) {
      return WhitelistOperationResult.ERROR_EXISTING_ENTRY;
    }

    final List<String> oldWhitelist = new ArrayList<>(this.accountWhitelist);
    this.accountWhitelist.addAll(accounts);
    try {
      verifyConfigurationFileState(oldWhitelist);
      updateConfigurationFile(accountWhitelist);
      verifyConfigurationFileState(accountWhitelist);
    } catch (IOException e) {
      revertState(oldWhitelist);
      return WhitelistOperationResult.ERROR_WHITELIST_PERSIST_FAIL;
    } catch (WhitelistFileSyncException e) {
      return WhitelistOperationResult.ERROR_WHITELIST_FILE_SYNC;
    }
    return WhitelistOperationResult.SUCCESS;
  }

  public WhitelistOperationResult removeAccounts(final List<String> accounts) {
    final WhitelistOperationResult inputValidationResult = inputValidation(accounts);
    if (inputValidationResult != WhitelistOperationResult.SUCCESS) {
      return inputValidationResult;
    }

    if (!accountWhitelist.containsAll(accounts)) {
      return WhitelistOperationResult.ERROR_ABSENT_ENTRY;
    }

    final List<String> oldWhitelist = new ArrayList<>(this.accountWhitelist);

    this.accountWhitelist.removeAll(accounts);
    try {
      verifyConfigurationFileState(oldWhitelist);
      updateConfigurationFile(accountWhitelist);
      verifyConfigurationFileState(accountWhitelist);
    } catch (IOException e) {
      revertState(oldWhitelist);
      return WhitelistOperationResult.ERROR_WHITELIST_PERSIST_FAIL;
    } catch (WhitelistFileSyncException e) {
      return WhitelistOperationResult.ERROR_WHITELIST_FILE_SYNC;
    }
    return WhitelistOperationResult.SUCCESS;
  }

  private WhitelistOperationResult inputValidation(final List<String> accounts) {
    if (accounts == null || accounts.isEmpty()) {
      return WhitelistOperationResult.ERROR_EMPTY_ENTRY;
    }

    if (containsInvalidAccount(accounts)) {
      return WhitelistOperationResult.ERROR_INVALID_ENTRY;
    }

    if (inputHasDuplicates(accounts)) {
      return WhitelistOperationResult.ERROR_DUPLICATED_ENTRY;
    }

    return WhitelistOperationResult.SUCCESS;
  }

  private void verifyConfigurationFileState(final Collection<String> oldAccounts)
      throws IOException, WhitelistFileSyncException {
    whitelistPersistor.verifyConfigFileMatchesState(
        WhitelistPersistor.WHITELIST_TYPE.ACCOUNTS, oldAccounts);
  }

  private void updateConfigurationFile(final Collection<String> accounts) throws IOException {
    whitelistPersistor.updateConfig(WhitelistPersistor.WHITELIST_TYPE.ACCOUNTS, accounts);
  }

  private void revertState(final List<String> accountWhitelist) {
    this.accountWhitelist = accountWhitelist;
  }

  private boolean inputHasDuplicates(final List<String> accounts) {
    return !accounts.stream().allMatch(new HashSet<>()::add);
  }

  public boolean contains(final String account) {
    return (accountWhitelist.contains(account));
  }

  public List<String> getAccountWhitelist() {
    return new ArrayList<>(accountWhitelist);
  }

  private boolean containsInvalidAccount(final List<String> accounts) {
    return !accounts.stream().allMatch(AccountWhitelistController::isValidAccountString);
  }

  static boolean isValidAccountString(final String account) {
    try {
      if (account == null || !account.startsWith("0x")) {
        return false;
      }
      BytesValue bytesValue = BytesValue.fromHexString(account);
      return bytesValue.size() == ACCOUNT_BYTES_SIZE;
    } catch (NullPointerException | IndexOutOfBoundsException | IllegalArgumentException e) {
      return false;
    }
  }

  public synchronized void reload() throws RuntimeException {
    final ArrayList<String> currentAccountsList = new ArrayList<>(accountWhitelist);
    accountWhitelist.clear();

    try {
      final LocalPermissioningConfiguration updatedConfig =
          PermissioningConfigurationBuilder.permissioningConfiguration(
              configuration.isNodeWhitelistEnabled(),
              configuration.getNodePermissioningConfigFilePath(),
              configuration.isAccountWhitelistEnabled(),
              configuration.getAccountPermissioningConfigFilePath());
      readAccountsFromConfig(updatedConfig);
      configuration = updatedConfig;
    } catch (Exception e) {
      LOG.warn(
          "Error reloading permissions file. In-memory whitelisted accounts will be reverted to previous valid configuration. "
              + "Details: {}",
          e.getMessage());
      accountWhitelist.clear();
      accountWhitelist.addAll(currentAccountsList);
      throw new RuntimeException(e);
    }
  }
}
