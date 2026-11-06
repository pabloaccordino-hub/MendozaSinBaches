
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), permissionRequestCode
            )
        }
    }

    private fun startTripFromBridge(): String {
        if (!hasLocationPermission()) {
            runOnUiThread { requestBasicPermissions() }
            return "PERMISSION_REQUIRED"
        }

        return try {
            val serviceIntent = Intent(this, TripTrackingService::class.java).apply {
                action = TripTrackingService.ACTION_START
            }

            // El servicio se inicia desde una Activity visible; esto es
            // importante para las restricciones de ubicación en Android moderno.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            "STARTED"
        } catch (_: SecurityException) {
            "PERMISSION_REQUIRED"
        } catch (_: Exception) {
            "START_FAILED"
        }
    }

    private fun stopTripFromBridge(): String {
        return try {
            val serviceIntent = Intent(this, TripTrackingService::class.java).apply {
                action = TripTrackingService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            "STOPPING"
        } catch (_: Exception) {
            "STOP_FAILED"
        }
    }

    inner class TripBridge {
        @JavascriptInterface
        fun startTrip(): String = startTripFromBridge()

        @JavascriptInterface
        fun stopTrip(): String = stopTripFromBridge()

        @JavascriptInterface
        fun getPendingDetections(): String =
            TripTrackingService.getPendingDetections(this@MainActivity)

        @JavascriptInterface
        fun clearPendingDetections() {
            TripTrackingService.clearPendingDetections(this@MainActivity)
        }
    }

    @Deprecated("Deprecated for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == fileChooserRequestCode) {
            val result = if (resultCode == Activity.RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        }
    }

    @Deprecated("Deprecated in Android 13")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("MSBTrip")
            webView.destroy()
        }
        super.onDestroy()
    }
}
