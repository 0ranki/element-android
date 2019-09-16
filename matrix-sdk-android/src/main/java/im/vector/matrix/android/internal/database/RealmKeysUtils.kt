/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.matrix.android.internal.database

import android.content.Context
import android.util.Base64
import im.vector.matrix.android.BuildConfig
import im.vector.matrix.android.api.util.SecretStoringUtils
import io.realm.RealmConfiguration
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject

/**
 * On creation a random key is generated, this key is then encrypted using the system KeyStore.
 * The encrypted key is stored in shared preferences.
 * When the database is opened again, the encrypted key is taken from the shared pref,
 * then the Keystore is used to decrypt the key. The decrypted key is passed to the RealConfiguration.
 *
 * On android >=M, the KeyStore generates an AES key to encrypt/decrypt the database key,
 * and the encrypted key is stored with the initialization vector in base64 in the shared pref.
 * On android <M, the KeyStore cannot create AES keys, so a public/private key pair is generated,
 * then we generate a random secret key. The database key is encrypted with the secret key; The secret
 * key is encrypted with the public RSA key and stored with the encrypted key in the shared pref
 */
internal class RealmKeysUtils @Inject constructor(private val context: Context) {

    private val rng = SecureRandom()

    private val sharedPreferences = context.getSharedPreferences("im.vector.matrix.android.keys", Context.MODE_PRIVATE)

    private fun generateKeyForRealm(): ByteArray {
        val keyForRealm = ByteArray(RealmConfiguration.KEY_LENGTH)
        rng.nextBytes(keyForRealm)
        return keyForRealm
    }

    /**
     * Check if there is already a key for this alias
     */
    private fun hasKeyForDatabase(alias: String): Boolean {
        return sharedPreferences.contains("${ENCRYPTED_KEY_PREFIX}_$alias")
    }

    /**
     * Creates a new secure random key for this database.
     * The random key is then encrypted by the keystore, and the encrypted key is stored
     * in shared preferences.
     *
     * @return the generated key (can be passed to Realm Configuration)
     */
    private fun createAndSaveKeyForDatabase(alias: String): ByteArray {
        val key = generateKeyForRealm()
        val encodedKey = Base64.encodeToString(key, Base64.NO_PADDING)
        val toStore = SecretStoringUtils.securelyStoreString(encodedKey, alias, context)
        sharedPreferences
                .edit()
                .putString("${ENCRYPTED_KEY_PREFIX}_$alias", Base64.encodeToString(toStore!!, Base64.NO_PADDING))
                .apply()
        return key
    }

    /**
     * Retrieves the key for this database
     * throws if something goes wrong
     */
    private fun extractKeyForDatabase(alias: String): ByteArray {
        val encryptedB64 = sharedPreferences.getString("${ENCRYPTED_KEY_PREFIX}_$alias", null)
        val encryptedKey = Base64.decode(encryptedB64, Base64.NO_PADDING)
        val b64 = SecretStoringUtils.loadSecureSecret(encryptedKey, alias, context)
        return Base64.decode(b64!!, Base64.NO_PADDING)
    }

    fun configureEncryption(realmConfigurationBuilder: RealmConfiguration.Builder, alias: String) {
        val key = if (hasKeyForDatabase(alias)) {
            Timber.i("Found key for alias:$alias")
            extractKeyForDatabase(alias)
        } else {
            Timber.i("Create key for DB alias:$alias")
            createAndSaveKeyForDatabase(alias)
        }

        if (BuildConfig.LOG_PRIVATE_DATA) {
            val log = key.joinToString("") { "%02x".format(it) }
            Timber.w("Database key for alias `$alias`: $log")
        }

        realmConfigurationBuilder.encryptionKey(key)
    }

    // Delete elements related to the alias
    fun clear(alias: String) {
        if (hasKeyForDatabase(alias)) {
            SecretStoringUtils.safeDeleteKey(alias)

            sharedPreferences
                    .edit()
                    .remove("${ENCRYPTED_KEY_PREFIX}_$alias")
                    .apply()
        }
    }

    companion object {
        private const val ENCRYPTED_KEY_PREFIX = "REALM_ENCRYPTED_KEY"
    }
}