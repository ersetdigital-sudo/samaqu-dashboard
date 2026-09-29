package com.samaqu.keyboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.samaqu.keyboard.R
import com.samaqu.keyboard.util.SamaQuText

class InvoiceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_invoice, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etBuyer = view.findViewById<EditText>(R.id.etBuyer)
        val etProduct = view.findViewById<EditText>(R.id.etProduct)
        val etQty = view.findViewById<EditText>(R.id.etQty)
        val etPrice = view.findViewById<EditText>(R.id.etPrice)
        val etOngkir = view.findViewById<EditText>(R.id.etOngkir)
        val etPayment = view.findViewById<EditText>(R.id.etPayment)
        val etResult = view.findViewById<EditText>(R.id.etResult)
        val resultCard = view.findViewById<View>(R.id.resultCard)

        view.findViewById<View>(R.id.btnGenerate).setOnClickListener {
            val buyer = etBuyer.text.toString().trim()
            val product = etProduct.text.toString().trim()
            val qty = etQty.text.toString().toIntOrNull() ?: 1
            val price = etPrice.text.toString().toDoubleOrNull() ?: 0.0
            val ongkir = etOngkir.text.toString().toDoubleOrNull() ?: 0.0
            val payment = etPayment.text.toString().trim()

            etResult.setText(
                SamaQuText.fullInvoice(buyer, product, qty, price, ongkir, payment)
            )
            resultCard.visibility = View.VISIBLE
        }

        view.findViewById<View>(R.id.btnCopy).setOnClickListener {
            val cm = requireContext().getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("invoice", etResult.text.toString()))
            Toast.makeText(requireContext(), "Invoice disalin!", Toast.LENGTH_SHORT).show()
        }
    }
}
