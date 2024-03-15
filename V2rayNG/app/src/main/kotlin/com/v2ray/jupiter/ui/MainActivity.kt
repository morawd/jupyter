package com.v2ray.jupiter.ui

import android.Manifest
import android.app.ProgressDialog
import android.content.*
import android.net.Uri
import android.net.VpnService
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import com.tbruyelle.rxpermissions.RxPermissions
import com.v2ray.jupiter.R
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import com.v2ray.jupiter.AppConfig
import android.content.res.ColorStateList
import android.os.Build
import com.google.android.material.navigation.NavigationView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.recyclerview.widget.ItemTouchHelper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.jupiter.AppConfig.ANG_PACKAGE
import com.v2ray.jupiter.BuildConfig
import com.v2ray.jupiter.databinding.ActivityMainBinding
import com.v2ray.jupiter.dto.EConfigType
import com.v2ray.jupiter.dto.ServerConfig
import com.v2ray.jupiter.dto.V2rayConfig
import com.v2ray.jupiter.dto.VmessQRCode
import com.v2ray.jupiter.extension.idnHost
import com.v2ray.jupiter.extension.removeWhiteSpace
import com.v2ray.jupiter.extension.toast
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.util.concurrent.TimeUnit
import com.v2ray.jupiter.helper.SimpleItemTouchHelperCallback
import com.v2ray.jupiter.service.V2RayServiceManager
import com.v2ray.jupiter.util.*
import com.v2ray.jupiter.viewmodel.MainViewModel
import kotlinx.coroutines.*
import me.drakeet.support.toast.ToastCompat
import java.io.File
import java.io.FileOutputStream
import okhttp3.*
import java.io.IOException
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.Executors
import java.util.Base64
import java.nio.charset.StandardCharsets
import com.google.gson.reflect.TypeToken

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var binding: ActivityMainBinding

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                Utils.stopVService(this)
            } else if (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN" == "VPN") {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)


        val toggle = ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        "v${BuildConfig.VERSION_NAME} (${SpeedtestUtil.getLibVersion()})".also { binding.version.text = it }

        setupViewModel()
        copyAssets()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RxPermissions(this)
                .request(Manifest.permission.POST_NOTIFICATIONS)
                .subscribe {
                    if (!it)
                        toast(R.string.toast_permission_denied)
                }
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                if (!Utils.getDarkModeStatus(this)) {
                    binding.fab.setImageResource(R.drawable.ic_stat_name)
                }
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_orange))
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
            } else {
                if (!Utils.getDarkModeStatus(this)) {
                    binding.fab.setImageResource(R.drawable.ic_stat_name)
                }
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_grey))
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
            }
            hideCircle()
        }
        mainViewModel.startListenBroadcast()
    }

    private fun copyAssets() {
        val extFolder = Utils.userAssetPath(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geo = arrayOf("geosite.dat", "geoip.dat")
                assets.list("")
                        ?.filter { geo.contains(it) }
                        ?.filter { !File(extFolder, it).exists() }
                        ?.forEach {
                            val target = File(extFolder, it)
                            assets.open(it).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            Log.i(ANG_PACKAGE, "Copied from apk assets folder to ${target.absolutePath}")
                        }
            } catch (e: Exception) {
                Log.e(ANG_PACKAGE, "asset copy failed", e)
            }
        }
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.migrateLegacyConfig(this@MainActivity)
            if (result != null) {
                launch(Dispatchers.Main) {
                    if (result) {
                        toast(getString(R.string.migration_success))
                        mainViewModel.reloadServerList()
                    } else {
                        toast(getString(R.string.migration_fail))
                    }
                }
            }
        }
    }

    fun startV2Ray() {
        if (mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).isNullOrEmpty()) {
            return
        }
        showCircle()
//        toast(R.string.toast_services_start)
        V2RayServiceManager.startV2Ray(this)
        hideCircle()
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            Utils.stopVService(this)
        }
        Observable.timer(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    startV2Ray()
                }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    private suspend fun fetchConfigsFromUrl2(url: String): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IOException("Unexpected response code: ${response.code}")
                }

                response.body?.string() ?: ""
            } catch (e: IOException) {
                e.printStackTrace()
                ""
            }
        }
    }
    suspend fun pingAsync(config: String): Long {
        return withContext(Dispatchers.IO) {
            SpeedtestUtil.realPing(config)
        }
    }

    public fun convertConfig(
        str: String,
        subid: String?,
        removedSelectedServer: ServerConfig?
    ):Any{
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return R.string.toast_none_data
            }

            //maybe sub
            if (TextUtils.isEmpty(subid) && (str.startsWith(AppConfig.PROTOCOL_HTTP) || str.startsWith(
                    AppConfig.PROTOCOL_HTTPS
                ))
            ) {
                MmkvManager.importUrlAsSubscription(str)
                return 0
            }

            var config: ServerConfig? = null
            val allowInsecure = AngConfigManager.settingsStorage?.decodeBool(AppConfig.PREF_ALLOW_INSECURE) ?: false
            if (str.startsWith(EConfigType.VMESS.protocolScheme)) {
                config = ServerConfig.create(EConfigType.VMESS)
                val streamSetting = config.outboundBean?.streamSettings ?: return -1


                if (!AngConfigManager.tryParseNewVmess(str, config, allowInsecure)) {
                    if (str.indexOf("?") > 0) {
                        if (!AngConfigManager.tryResolveVmess4Kitsunebi(str, config)) {
                            return R.string.toast_incorrect_protocol
                        }
                    } else {
                        var result = str.replace(EConfigType.VMESS.protocolScheme, "")
                        result = Utils.decode(result)
                        if (TextUtils.isEmpty(result)) {
                            return R.string.toast_decoding_failed
                        }
                        val vmessQRCode = Gson().fromJson(result, VmessQRCode::class.java)
                        // Although VmessQRCode fields are non null, looks like Gson may still create null fields
                        if (TextUtils.isEmpty(vmessQRCode.add)
                            || TextUtils.isEmpty(vmessQRCode.port)
                            || TextUtils.isEmpty(vmessQRCode.id)
                            || TextUtils.isEmpty(vmessQRCode.net)
                        ) {
                            return R.string.toast_incorrect_protocol
                        }

                        config.remarks = vmessQRCode.ps
                        config.outboundBean?.settings?.vnext?.get(0)?.let { vnext ->
                            vnext.address = vmessQRCode.add
                            vnext.port = Utils.parseInt(vmessQRCode.port)
                            vnext.users[0].id = vmessQRCode.id
                            vnext.users[0].security =
                                if (TextUtils.isEmpty(vmessQRCode.scy)) V2rayConfig.DEFAULT_SECURITY else vmessQRCode.scy
                            vnext.users[0].alterId = Utils.parseInt(vmessQRCode.aid)
                        }
                        val sni = streamSetting.populateTransportSettings(
                            vmessQRCode.net,
                            vmessQRCode.type,
                            vmessQRCode.host,
                            vmessQRCode.path,
                            vmessQRCode.path,
                            vmessQRCode.host,
                            vmessQRCode.path,
                            vmessQRCode.type,
                            vmessQRCode.path
                        )

                        val fingerprint = vmessQRCode.fp ?: streamSetting.tlsSettings?.fingerprint
                        streamSetting.populateTlsSettings(
                            vmessQRCode.tls, allowInsecure,
                            if (TextUtils.isEmpty(vmessQRCode.sni)) sni else vmessQRCode.sni,
                            fingerprint, vmessQRCode.alpn, null, null, null
                        )
                    }
                }
            } else if (str.startsWith(EConfigType.SHADOWSOCKS.protocolScheme)) {
                config = ServerConfig.create(EConfigType.SHADOWSOCKS)
                if (!AngConfigManager.tryResolveResolveSip002(str, config)) {
                    var result = str.replace(EConfigType.SHADOWSOCKS.protocolScheme, "")
                    val indexSplit = result.indexOf("#")
                    if (indexSplit > 0) {
                        try {
                            config.remarks =
                                Utils.urlDecode(result.substring(indexSplit + 1, result.length))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        result = result.substring(0, indexSplit)
                    }

                    //part decode
                    val indexS = result.indexOf("@")
                    result = if (indexS > 0) {
                        Utils.decode(result.substring(0, indexS)) + result.substring(
                            indexS,
                            result.length
                        )
                    } else {
                        Utils.decode(result)
                    }

                    val legacyPattern = "^(.+?):(.*)@(.+?):(\\d+?)/?$".toRegex()
                    val match = legacyPattern.matchEntire(result)
                        ?: return R.string.toast_incorrect_protocol

                    config.outboundBean?.settings?.servers?.get(0)?.let { server ->
                        server.address = match.groupValues[3].removeSurrounding("[", "]")
                        server.port = match.groupValues[4].toInt()
                        server.password = match.groupValues[2]
                        server.method = match.groupValues[1].lowercase()
                    }
                }
            } else if (str.startsWith(EConfigType.SOCKS.protocolScheme)) {
                var result = str.replace(EConfigType.SOCKS.protocolScheme, "")
                val indexSplit = result.indexOf("#")
                config = ServerConfig.create(EConfigType.SOCKS)
                if (indexSplit > 0) {
                    try {
                        config.remarks =
                            Utils.urlDecode(result.substring(indexSplit + 1, result.length))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    result = result.substring(0, indexSplit)
                }

                //part decode
                val indexS = result.indexOf("@")
                if (indexS > 0) {
                    result = Utils.decode(result.substring(0, indexS)) + result.substring(
                        indexS,
                        result.length
                    )
                } else {
                    result = Utils.decode(result)
                }

                val legacyPattern = "^(.*):(.*)@(.+?):(\\d+?)$".toRegex()
                val match =
                    legacyPattern.matchEntire(result) ?: return R.string.toast_incorrect_protocol

                config.outboundBean?.settings?.servers?.get(0)?.let { server ->
                    server.address = match.groupValues[3].removeSurrounding("[", "]")
                    server.port = match.groupValues[4].toInt()
                    val socksUsersBean =
                        V2rayConfig.OutboundBean.OutSettingsBean.ServersBean.SocksUsersBean()
                    socksUsersBean.user = match.groupValues[1].lowercase()
                    socksUsersBean.pass = match.groupValues[2]
                    server.users = listOf(socksUsersBean)
                }
            } else if (str.startsWith(EConfigType.TROJAN.protocolScheme)) {
                val uri = URI(Utils.fixIllegalUrl(str))
                config = ServerConfig.create(EConfigType.TROJAN)
                config.remarks = Utils.urlDecode(uri.fragment ?: "")

                var flow = ""
                var fingerprint = config.outboundBean?.streamSettings?.tlsSettings?.fingerprint
                if (uri.rawQuery != null) {
                    val queryParam = uri.rawQuery.split("&")
                        .associate { it.split("=").let { (k, v) -> k to Utils.urlDecode(v) } }

                    val sni = config.outboundBean?.streamSettings?.populateTransportSettings(
                        queryParam["type"] ?: "tcp",
                        queryParam["headerType"],
                        queryParam["host"],
                        queryParam["path"],
                        queryParam["seed"],
                        queryParam["quicSecurity"],
                        queryParam["key"],
                        queryParam["mode"],
                        queryParam["serviceName"]
                    )
                    fingerprint = queryParam["fp"] ?: ""
                    config.outboundBean?.streamSettings?.populateTlsSettings(
                        queryParam["security"] ?: V2rayConfig.TLS,
                        allowInsecure, queryParam["sni"] ?: sni!!, fingerprint, queryParam["alpn"],
                        null, null, null
                    )
                    flow = queryParam["flow"] ?: ""
                } else {
                    config.outboundBean?.streamSettings?.populateTlsSettings(
                        V2rayConfig.TLS, allowInsecure, "",
                        fingerprint, null, null, null, null
                    )
                }

                config.outboundBean?.settings?.servers?.get(0)?.let { server ->
                    server.address = uri.idnHost
                    server.port = uri.port
                    server.password = uri.userInfo
                    server.flow = flow
                }
            } else if (str.startsWith(EConfigType.VLESS.protocolScheme)) {
                val uri = URI(Utils.fixIllegalUrl(str))
                val queryParam = uri.rawQuery.split("&")
                    .associate { it.split("=").let { (k, v) -> k to Utils.urlDecode(v) } }
                config = ServerConfig.create(EConfigType.VLESS)
                val streamSetting = config.outboundBean?.streamSettings ?: return -1
                var fingerprint = streamSetting.tlsSettings?.fingerprint

                config.remarks = Utils.urlDecode(uri.fragment ?: "")
                config.outboundBean?.settings?.vnext?.get(0)?.let { vnext ->
                    vnext.address = uri.idnHost
                    vnext.port = uri.port
                    vnext.users[0].id = uri.userInfo
                    vnext.users[0].encryption = queryParam["encryption"] ?: "none"
                    vnext.users[0].flow = queryParam["flow"] ?: ""
                }

                val sni = streamSetting.populateTransportSettings(
                    queryParam["type"] ?: "tcp",
                    queryParam["headerType"],
                    queryParam["host"],
                    queryParam["path"],
                    queryParam["seed"],
                    queryParam["quicSecurity"],
                    queryParam["key"],
                    queryParam["mode"],
                    queryParam["serviceName"]
                )
                fingerprint = queryParam["fp"] ?: ""
                val pbk = queryParam["pbk"] ?: ""
                val sid = queryParam["sid"] ?: ""
                val spx = Utils.urlDecode(queryParam["spx"] ?: "")
                streamSetting.populateTlsSettings(
                    queryParam["security"] ?: "", allowInsecure,
                    queryParam["sni"] ?: sni, fingerprint, queryParam["alpn"], pbk, sid, spx
                )
            } else if (str.startsWith(EConfigType.WIREGUARD.protocolScheme)) {
                val uri = URI(Utils.fixIllegalUrl(str))
                config = ServerConfig.create(EConfigType.WIREGUARD)
                config.remarks = Utils.urlDecode(uri.fragment ?: "")

                if (uri.rawQuery != null) {
                    val queryParam = uri.rawQuery.split("&")
                        .associate { it.split("=").let { (k, v) -> k to Utils.urlDecode(v) } }

                    config.outboundBean?.settings?.let { wireguard ->
                        wireguard.secretKey = uri.userInfo
                        wireguard.address =
                            (queryParam["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4).removeWhiteSpace()
                                .split(",")
                        wireguard.peers?.get(0)?.publicKey = queryParam["publickey"] ?: ""
                        wireguard.peers?.get(0)?.endpoint = "${uri.idnHost}:${uri.port}"
                        wireguard.mtu = Utils.parseInt(queryParam["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
                        wireguard.reserved =
                            (queryParam["reserved"] ?: "0,0,0").removeWhiteSpace().split(",")
                                .map { it.toInt() }
                    }
                }
            }
            if (config == null) {
                return R.string.toast_incorrect_protocol
            }
            config.subscriptionId = "64532324"
            return config
            val guid = MmkvManager.encodeServerConfig("", config)
            if (removedSelectedServer != null &&
                config.getProxyOutbound()
                    ?.getServerAddress() == removedSelectedServer.getProxyOutbound()
                    ?.getServerAddress() &&
                config.getProxyOutbound()
                    ?.getServerPort() == removedSelectedServer.getProxyOutbound()
                    ?.getServerPort()
            ) {
//                AngConfigManager.mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, guid)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        }


        return 0
    }
    fun decodeVmessToJSON(vmessUri: String): String {
        // Remove "vmess://" prefix
        val encodedConfig = vmessUri.substringAfter("://")
        // Ensure proper padding
        val paddedString = if (encodedConfig.length % 4 == 0) {
            encodedConfig
        } else {
            encodedConfig.padEnd(encodedConfig.length + (4 - encodedConfig.length % 4), '=')
        }
        Log.d("sssss222",encodedConfig)
        // Decode base64
        val decodedBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getDecoder().decode(paddedString)
        } else {
            TODO("VERSION.SDK_INT < O")
        }
        val decodedString = String(decodedBytes, StandardCharsets.UTF_8)

        return decodedString
    }
     suspend fun fetchServers(url: String): Any {
         return return withContext(Dispatchers.IO) {
             try {
                 val servers = fetchConfigsFromUrl2(url)
                 // Process the configurations here
                 if (!servers.equals(null)) {
                     servers
                 } else {
                     println("No configurations received.")
                 }
             } catch (e: Exception) {
                 e.printStackTrace()
                 println("Failed to fetch configurations: ${e.message}")
             }
         }

    }


    fun fetchConfigs(url: String) {
        Log.d("tr11111111111111111", url.toString())

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val configs = fetchConfigsFromUrl2(url)
                // Process the configurations here
                if (!configs.equals(null)) {
                    // Do something with the configurations
                    println("Received configurations: $configs")
                    var configsArray = configs.trim().split("\n")
                    configsArray = configsArray.shuffled()

//                    val jsonConfig = decodeVmessToJSON(configsArray[0])
//                    for(conf in configsArray) {
//                        val outputConf = convertConfig(conf, "53453453", null)
//                        val result = SpeedtestUtil.realPing(outputConf.toString())
//                        if(result.toInt() !=-1){
//                            Log.d("222222213141",result.toString())
//                        }
//                    }
//                    Log.d("config json", jsonConfig.toString())





//                    Log.d("Configurations", configs)
//
//                    val batchSize = 10 // or any other desired batch size
//                    var startIndex = 0
//                    while (startIndex < configsArray.size) {
//                        val endIndex = minOf(startIndex + batchSize, configsArray.size)
//                        val batchConfigs = configsArray.subList(startIndex, endIndex)
//                        importBatchConfigurations(batchConfigs)
//                        startIndex = endIndex
//                    }
                    val healthyConfig =  ArrayList<String>()
                    Log.d("config size", configsArray.size.toString())

//                    var noConfigNeed = 2
//                      for(conf in configsArray){
////                          val configObj = MmkvManager.decodeServerConfig(config)
//                          val config = ServerConfig.create(EConfigType.CUSTOM)
//                          config.remarks = System.currentTimeMillis().toString()
////                          config.subscriptionId = 123
//                          config.fullConfig = Gson().fromJson(conf, V2rayConfig::class.java)
////                          val configObj = V2rayConfigUtil.getV2rayConfig(getApplication(), config.guid)
//                          Log.d("config result", config.toString())
//
////                          Log.d("configss", config)
////                          delay(1000)
////                          val realTestScope by lazy { CoroutineScope(Executors.newFixedThreadPool(10).asCoroutineDispatcher()) }
////                          val result = SpeedtestUtil.realPing(config)
//////                          realTestScope.launch {
//////                              val result = SpeedtestUtil.realPing(config)
//////                              MessageUtil.sendMsg2UI(this@V2RayTestService,
//////                                  AppConfig.MSG_MEASURE_CONFIG_SUCCESS, Pair(contentPair.first, result))
//////                          }
////                          Log.d("config result", result.toString())
////                          toast(result.toString())
////                          if(result.toInt() != -1){
////                              healthyConfig += config
////                              Log.d("config healthy", config)
////
////                              noConfigNeed -= 1
////                          }
//////                          if(noConfigNeed == 0) break
////
//                      }
//                    Log.d("config healthy", healthyConfig.toString())
//                    importBatchConfig(configsArray.joinToString("\n"))
                    importBatchConfig(configsArray.joinToString("\n"))
//                    migrateLegacy()
                    mainViewModel.testAllRealPing()

                } else {
                    println("No configurations received.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("Failed to fetch configurations: ${e.message}")
            }
        }
    }
    data class ServerObject(
        val servers: List<String>
    )
    data class Server(
        val provider: String,
        val type: String,
        val server: String
    )

    data class ServerResponse(
        val servers: List<Server>
    )

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_web -> {
//            importQRcode(true)
//            toast("fetching new server from web ...")
//            val items = arrayOf("Server1", "Server2", "Server3", "Server4", "Server5", "Server6", "Server7", "Server8")
//            val urls = arrayOf(
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub2.txt",
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub3.txt",
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub4.txt",
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub5.txt",
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub6.txt",
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub7.txt",
//                "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub8.txt"
//            )

            GlobalScope.launch(Dispatchers.IO) {
                val serversResponseJson = fetchServers("https://raw.githubusercontent.com/timingos/jupyter-public-worker/main/api.json")
                Log.d("tr77777777", serversResponseJson.toString())

                val gson = Gson()
                val serverResponse: ServerResponse = gson.fromJson(serversResponseJson.toString(), ServerResponse::class.java)
                val servers = serverResponse.servers

                Log.d("tr999999999", servers.toString())
                val items = servers.map { "${it.provider} + ${it.type}" }.toTypedArray()
                val urls = servers.map { it.server }.toTypedArray()

                withContext(Dispatchers.Main) {
                    val builder = AlertDialog.Builder(this@MainActivity)
                    builder.setTitle("Choose a server")
                    builder.setItems(items) { dialog, which ->
                        // The 'which' argument contains the index position of the selected item
                        Toast.makeText(applicationContext, "Fetching configs from ${items[which]}", Toast.LENGTH_SHORT).show()

                        GlobalScope.launch(Dispatchers.IO) {
                            showCircle()
                            fetchConfigs(urls[which])
                            // hideCircle()
                        }
                    }
                    builder.setNegativeButton("Cancel", null)
                    builder.show()
                }
            }



//            GlobalScope.launch(Dispatchers.IO) {
//                val serversResponseJson = fetchServers("https://raw.githubusercontent.com/timingos/jupyter-public-worker/main/api.json")
//
//                val gson = Gson()
//                val serverListType = object : TypeToken<List<Server>>() {}.type
//                val servers: List<Server> = gson.fromJson(serversResponseJson, serverListType)
//
//                val items = servers.map { "${it.provider} + ${it.type}" }.toTypedArray()
//                val urls = servers.map { it.server }.toTypedArray()
//
//                withContext(Dispatchers.Main) {
//                    val builder = AlertDialog.Builder(this@MainActivity)
//                    builder.setTitle("Choose a server")
//                    builder.setItems(items) { dialog, which ->
//                        // The 'which' argument contains the index position of the selected item
//                        Toast.makeText(applicationContext, "Fetching configs from ${items[which]}", Toast.LENGTH_SHORT).show()
//
//                        GlobalScope.launch(Dispatchers.IO) {
//                            showCircle()
//                            fetchConfigs(urls[which])
//                            // hideCircle()
//                        }
//                    }
//                    builder.setNegativeButton("Cancel", null)
//                    builder.show()
//                }
//            }




//            GlobalScope.launch(Dispatchers.IO) {
//                fetchConfigs("https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub8.txt")
//            }
//            GlobalScope.launch(Dispatchers.IO) {
//                fetchConfigs("https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub7.txt")
//            }
//            GlobalScope.launch(Dispatchers.IO) {
//                fetchConfigs("https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub7.txt")
//            }

//            fetchConfigsFromUrl("https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub8.txt")
//            importBatchConfig("ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpINmNTVGY4dkRiVkgxY3BuckF4Rmoy@18.218.239.120:36326#%F0%9F%87%BA%F0%9F%87%B8US-18.218.239.120-0841")
            true
        }

        R.id.import_qrcode -> {
            importQRcode(true)
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }
        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }
        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }
        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }
        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }
        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }
        R.id.import_config_custom_clipboard -> {
            importConfigCustomClipboard()
            true
        }
        R.id.import_config_custom_local -> {
            importConfigCustomLocal()
            true
        }
        R.id.import_config_custom_url -> {
            importConfigCustomUrlClipboard()
            true
        }
        R.id.import_config_custom_url_scan -> {
            importQRcode(false)
            true
        }

//        R.id.sub_setting -> {
//            startActivity<SubSettingActivity>()
//            true
//        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.export_all -> {
            if (AngConfigManager.shareNonCustomConfigsToClipboard(this, mainViewModel.serverList) == 0) {
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            true
        }

        R.id.ping_all -> {
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        MmkvManager.removeAllServer()
                        mainViewModel.reloadServerList()
                    }
                    .show()
            true
        }
        R.id.del_duplicate_config-> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    mainViewModel.removeDuplicateServer()
                }
                .show()
            true
        }
        R.id.del_invalid_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeInvalidServer()
                    mainViewModel.reloadServerList()
                }
                .show()
            true
        }
        R.id.sort_by_test_results -> {
            MmkvManager.sortByTestResults()
            mainViewModel.reloadServerList()
            true
        }
        R.id.filter_config -> {
            mainViewModel.filterConfig(this)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

//    fun realTest (config){
//        val result = SpeedtestUtil.realPing(contentPair.second)
//    }
    fun importBatchConfigurations(configs: List<String>) {
        configs.forEach { config ->
            try {
                importBatchConfig(config)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    private fun fetchConfigsFromUrl(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Handle failure
                e.printStackTrace()
//                runOnUiThread { toast("Failed to fetch configurations from the web.") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        // Handle unsuccessful response
//                        runOnUiThread { toast("Failed to fetch configurations from the web.") }
                        throw IOException("Unexpected response code: $res")
                    }

                    // Read and log the configurations
                    val configs = res.body?.string()
                    if (!configs.isNullOrEmpty()) {

                        var configsArray = configs.trim().split("\n")
//                        configsArray.shuffled()
                        configsArray = configsArray.shuffled()
                        Log.d("Configurations", configs)

//                        val finalConfigs = configsArray.joinToString("\n")

//                        configsArray.forEach { config ->
////                            importBatchConfig(config)
//                            try {
//                                importBatchConfig(config)
//                            } catch (e: Exception) {
//                                e.printStackTrace()
//                            }
//                        }


                        try {
                                importBatchConfig(configsArray[0])
                            } catch (e: Exception) {
                            Log.d("error222", e.message.toString())

                            e.printStackTrace()
                            }

//                        val batchSize = 10 // or any other desired batch size
//                        var startIndex = 0
//                        while (startIndex < configsArray.size) {
//                            val endIndex = minOf(startIndex + batchSize, configsArray.size)
//                            val batchConfigs = configsArray.subList(startIndex, endIndex)
//                            importBatchConfigurations(batchConfigs)
//                            startIndex = endIndex
//                        }

//                        mainViewModel.reloadServerList()


                    } else {
//                        runOnUiThread { toast("No configurations found.") }
                    }
                }
            }
        })
    }

    private fun importManually(createConfigType : Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    fun importQRcode(forConfig: Boolean): Boolean {
//        try {
//            startActivityForResult(Intent("com.google.zxing.client.android.SCAN")
//                    .addCategory(Intent.CATEGORY_DEFAULT)
//                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), requestCode)
//        } catch (e: Exception) {
        RxPermissions(this)
                .request(Manifest.permission.CAMERA)
                .subscribe {
                    if (it)
                        if (forConfig)
                            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
                        else
                            scanQRCodeForUrlToCustomConfig.launch(Intent(this, ScannerActivity::class.java))
                    else
                        toast(R.string.toast_permission_denied)
                }
//        }
        return true
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    private val scanQRCodeForUrlToCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importConfigCustomUrl(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    /**
     * import config from clipboard
     */
    fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importBatchConfig(server: String?, subid: String = "") {
        val subid2 = if(subid.isNullOrEmpty()){
            mainViewModel.subscriptionId
        }else{
            subid
        }
        val append = subid.isNullOrEmpty()

        var count = AngConfigManager.importBatchConfig(server, subid2, append)
        if (count <= 0) {
            count = AngConfigManager.importBatchConfig(Utils.decode(server!!), subid2, append)
        }
        if (count <= 0) {
            count = AngConfigManager.appendCustomConfigServer(server, subid2)
        }
        if (count > 0) {
            toast(R.string.toast_success)
            mainViewModel.reloadServerList()
        } else {
            toast(R.string.toast_failure)
        }
    }

    fun importConfigCustomClipboard()
            : Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from local config file
     */
    fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigCustomUrlClipboard()
            : Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from url
     */
    fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    Utils.getUrlContentWithCustomUserAgent(url)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                launch(Dispatchers.Main) {
                    importCustomizeConfig(configText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    fun importConfigViaSub()
            : Boolean {
        try {
            toast(R.string.title_sub_update)
            MmkvManager.decodeSubscriptions().forEach {
                if (TextUtils.isEmpty(it.first)
                        || TextUtils.isEmpty(it.second.remarks)
                        || TextUtils.isEmpty(it.second.url)
                ) {
                    return@forEach
                }
                if (!it.second.enabled) {
                    return@forEach
                }
                val url = Utils.idnToASCII(it.second.url)
                if (!Utils.isValidUrl(url)) {
                    return@forEach
                }
                Log.d(ANG_PACKAGE, url)
                lifecycleScope.launch(Dispatchers.IO) {
                    val configText = try {
                        Utils.getUrlContentWithCustomUserAgent(url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        launch(Dispatchers.Main) {
                            toast("\"" + it.second.remarks + "\" " + getString(R.string.toast_failure))
                        }
                        return@launch
                    }
                    launch(Dispatchers.Main) {
                        importBatchConfig(configText, it.first)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } catch (ex: ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        RxPermissions(this)
                .request(permission)
                .subscribe {
                    if (it) {
                        try {
                            contentResolver.openInputStream(uri).use { input ->
                                importCustomizeConfig(input?.bufferedReader()?.readText())
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else
                        toast(R.string.toast_permission_denied)
                }
    }

    /**
     * import customize config
     */
    fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                toast(R.string.toast_none_data)
                return
            }
            mainViewModel.appendCustomConfigServer(server)
            mainViewModel.reloadServerList()
            toast(R.string.toast_success)
            //adapter.notifyItemInserted(mainViewModel.serverList.lastIndex)
        } catch (e: Exception) {
            ToastCompat.makeText(this, "${getString(R.string.toast_malformed_josn)} ${e.cause?.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            return
        }
    }

    fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    fun showCircle() {
        binding.fabProgressCircle.show()
    }

    fun hideCircle() {
        try {
            Observable.timer(300, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe {
                        try {
                            if (binding.fabProgressCircle.isShown) {
                                binding.fabProgressCircle.hide()
                            }
                        } catch (e: Exception) {
                            Log.w(ANG_PACKAGE, e)
                        }
                    }
        } catch (e: Exception) {
            Log.d(ANG_PACKAGE, e.toString())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            //super.onBackPressed()
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            //R.id.server_profile -> activityClass = MainActivity::class.java
            R.id.sub_setting -> {
                startActivity(Intent(this, SubSettingActivity::class.java))
            }
            R.id.settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)
                        .putExtra("isRunning", mainViewModel.isRunning.value == true))
            }
            R.id.user_asset_setting -> {
                startActivity(Intent(this, UserAssetActivity::class.java))
            }
            R.id.feedback -> {
                Utils.openUri(this, AppConfig.v2rayNGIssues)
            }
            R.id.promotion -> {
                Utils.openUri(this, "${Utils.decode(AppConfig.promotionUrl)}?t=${System.currentTimeMillis()}")
            }
            R.id.logcat -> {
                startActivity(Intent(this, LogcatActivity::class.java))
            }
            R.id.privacy_policy-> {
                Utils.openUri(this, AppConfig.v2rayNGPrivacyPolicy)
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}
