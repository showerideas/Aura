package com.showerideas.aura.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.showerideas.aura.R
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.data.OnboardingPreferences
import com.showerideas.aura.databinding.ActivityMainBinding
import com.showerideas.aura.service.NearbyExchangeService
import com.showerideas.aura.service.VolumeButtonListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var onboardingPreferences: OnboardingPreferences
    @Inject lateinit var authPreferences: AuthPreferences

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // Required permissions for AURA — varies by API level
    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                // Required for Wi-Fi P2P used by Nearby Connections on API 33+
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            Timber.d("All permissions granted")
            onPermissionsGranted()
        } else {
            Timber.w("Permissions denied: $denied")
            // PR-03: show a rationale bottom sheet that deep-links to settings.
            PermissionRationaleBottomSheet
                .newInstance(denied.toList())
                .show(supportFragmentManager, PermissionRationaleBottomSheet.TAG)
        }
    }

    // Receives AURA_ACTIVATE broadcast from VolumeButtonListenerService
    private val activationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VolumeButtonListenerService.ACTION_AURA_ACTIVATE) {
                Timber.i("Activation broadcast received — launching exchange")
                // ExchangeFragment runs the gesture gate before starting the
                // NearbyExchangeService (PR-01). Do NOT start the service here.
                navController.navigate(R.id.exchangeFragment)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        checkAndRequestPermissions()
        registerActivationReceiver()
    }

    override fun onDestroy() {
        unregisterReceiver(activationReceiver)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp() || super.onSupportNavigateUp()

    // -------------------------------------------------------------------------

    private fun setupNavigation() {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        // PR-05: route first-run users to the onboarding flow before the
        // nav host inflates its default start destination. We use runBlocking
        // intentionally here — this is a one-off DataStore read on app start
        // and must complete before navigation can begin.
        val needsOnboarding = runBlocking { !onboardingPreferences.isOnboardingCompleteOnce() }
        if (needsOnboarding) {
            val graph = navController.navInflater.inflate(R.navigation.nav_graph)
            graph.setStartDestination(R.id.onboardingFragment)
            navController.graph = graph
        }

        val appBarConfig = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.profileFragment, R.id.contactsFragment)
        )
        setupActionBarWithNavController(navController, appBarConfig)
        binding.bottomNav.setupWithNavController(navController)

        // Hide the bottom nav on full-screen / flow destinations so the user
        // can't tap-bypass the onboarding gate or an in-flight exchange.
        // PR-05 + PR-08 + PR-09 contract.
        val rootDestinations = setOf(
            R.id.homeFragment, R.id.profileFragment, R.id.contactsFragment
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.visibility =
                if (destination.id in rootDestinations) android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onPermissionsGranted() {
        // PR-19: only start the background listener if the user hasn't
        // disabled it in Settings. Defaults to true so first-run behaviour
        // is unchanged.
        val bgEnabled = runBlocking { authPreferences.bgActivationEnabled.first() }
        if (bgEnabled) {
            VolumeButtonListenerService.start(this)
        }
    }

    private fun registerActivationReceiver() {
        val filter = IntentFilter(VolumeButtonListenerService.ACTION_AURA_ACTIVATE)
        // ContextCompat.registerReceiver handles the API 33+ RECEIVER_*_EXPORTED
        // flag requirement transparently across all minSdk levels we support;
        // it also satisfies the UnspecifiedRegisterReceiverFlag lint check that
        // was failing with the prior if/else branch.
        ContextCompat.registerReceiver(
            this,
            activationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // -----------------------------------------------------------------
    // PR-19: Settings entry point.
    //
    // The toolbar gear navigates to the SettingsFragment in the nav graph.
    // We use the classic onCreate/onSelect menu pair instead of
    // MenuProvider so existing fragments that already add their own
    // MenuProviders (e.g. ContactsFragment) don't collide on lifecycle
    // ordering.
    // -----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                if (navController.currentDestination?.id != R.id.settingsFragment) {
                    navController.navigate(R.id.settingsFragment)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
