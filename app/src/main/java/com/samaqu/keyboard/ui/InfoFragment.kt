package com.samaqu.keyboard.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.samaqu.keyboard.R

/**
 * Explains that a tab needs configuration before it can show anything, instead of
 * loading a web page that would demand a login this app does not have.
 */
class InfoFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.infoText).text =
            arguments?.getString(ARG_MESSAGE).orEmpty()

        view.findViewById<MaterialButton>(R.id.infoAction).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    companion object {
        private const val ARG_MESSAGE = "message"

        fun newInstance(message: String): InfoFragment = InfoFragment().apply {
            arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
        }
    }
}
