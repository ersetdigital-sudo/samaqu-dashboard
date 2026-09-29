package com.samaqu.keyboard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.samaqu.keyboard.R

class WebViewFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_webview, container, false)
        val wv = root as WebView
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = WebViewClient()
        wv.loadUrl(arguments?.getString(ARG_URL) ?: "about:blank")
        return root
    }

    fun canGoBack(): Boolean = (view as? WebView)?.canGoBack() == true

    fun goBack() {
        (view as? WebView)?.goBack()
    }

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String) = WebViewFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}
