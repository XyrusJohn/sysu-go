package com.sysu.go.activities

import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.sysu.go.R
import com.sysu.go.databinding.BottomSheetPhoneVerifyBinding
import java.util.concurrent.TimeUnit

class PhoneVerificationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPhoneVerifyBinding? = null
    private val binding get() = _binding!!

    private lateinit var etOtpFields: List<EditText>
    private var verificationId: String? = null
    private var countDownTimer: CountDownTimer? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    var onVerified: ((phoneNumber: String) -> Unit)? = null
    var onEditPhone: (() -> Unit)? = null

    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val ARG_PHONE = "phone"
        private const val RESEND_COOLDOWN_SECS = 60L

        fun newInstance(phoneNumber: String): PhoneVerificationBottomSheet {
            return PhoneVerificationBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_PHONE, phoneNumber) }
            }
        }
    }

    override fun getTheme(): Int = R.style.BottomSheetTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPhoneVerifyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val phoneNumber = arguments?.getString(ARG_PHONE) ?: run {
            dismiss()
            return
        }

        setupOtpInputs()
        setupButtons(phoneNumber)
        startResendCooldown(binding.btnResendOtp, binding.tvResendLabel)

        binding.etOtp1.postDelayed({
            binding.etOtp1.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etOtp1, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private fun setupOtpInputs() {
        etOtpFields = listOf(
            binding.etOtp1,
            binding.etOtp2,
            binding.etOtp3,
            binding.etOtp4,
            binding.etOtp5,
            binding.etOtp6
        )

        etOtpFields.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    showError("")
                }
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && index < etOtpFields.lastIndex) {
                        etOtpFields[index + 1].requestFocus()
                    }
                    updateVerifyButtonState()
                }
            })

            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL && editText.text.isNullOrEmpty() && index > 0) {
                    etOtpFields[index - 1].requestFocus()
                    etOtpFields[index - 1].setText("")
                }
                false
            }
        }
    }

    private fun setupButtons(phoneNumber: String) {
        binding.tvVerifySubtitle.text = "Enter the 6-digit code sent to\n$phoneNumber"

        binding.btnResendOtp.setOnClickListener {
            sendOtp(phoneNumber, forceResend = true)
        }

        binding.btnVerifyOtp.setOnClickListener {
            val code = etOtpFields.joinToString("") { it.text.toString() }
            if (code.length == 6) {
                verifyCode(code)
            }
        }
    }

    private fun startResendCooldown(btn: com.google.android.material.button.MaterialButton, label: android.widget.TextView) {
        countDownTimer?.cancel()
        btn.isEnabled = false

        countDownTimer = object : CountDownTimer(RESEND_COOLDOWN_SECS * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = millisUntilFinished / 1000
                label.text = "Resend code in ${secs}s"
            }

            override fun onFinish() {
                label.text = "Didn't receive a code?"
                btn.isEnabled = true
            }
        }.start()

        // Start OTP automatically
        sendOtp(arguments?.getString(ARG_PHONE) ?: return, forceResend = false)
    }

    private fun sendOtp(phoneNumber: String, forceResend: Boolean) {
        showLoading(true)

        val formattedNumber = formatToPhilippines(phoneNumber)

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(formattedNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(phoneAuthCallbacks)
            .apply {
                if (forceResend && resendToken != null) {
                    setForceResendingToken(resendToken!!)
                }
            }
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private val phoneAuthCallbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

        override fun onCodeSent(verId: String, token: PhoneAuthProvider.ForceResendingToken) {
            verificationId = verId
            resendToken = token
            showLoading(false)
        }

        override fun onCodeAutoRetrievalTimeOut(verId: String) {
            super.onCodeAutoRetrievalTimeOut(verId)
            showLoading(false)
        }

        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            // Firebase auto-retrieved the code — auto-fill all 6 fields
            val code = credential.smsCode ?: return
            fillOtpFields(code)
            verifyCode(code)
        }

        override fun onVerificationFailed(e: FirebaseException) {
            showLoading(false)
            showError(parseFirebaseError(e))
        }
    }

    private fun fillOtpFields(code: String) {
        if (code.length != 6) return
        etOtpFields.forEachIndexed { index, editText ->
            editText.setText(code[index].toString())
        }
    }

    private fun verifyCode(code: String) {
        val verId = verificationId
        if (verId.isNullOrEmpty()) {
            showError("Verification ID missing. Please request a new code.")
            return
        }

        val credential = PhoneAuthProvider.getCredential(verId, code)
        showLoading(true)

        val currentUser = auth.currentUser
        val task = if (currentUser != null) {
            // If already signed in (e.g. Google), link the phone number to the account
            currentUser.linkWithCredential(credential)
        } else {
            // If not signed in, sign in with the phone credential
            auth.signInWithCredential(credential)
        }

        task.addOnSuccessListener {
            showLoading(false)
            onVerified?.invoke(arguments?.getString(ARG_PHONE) ?: "")
            dismiss()
        }
        .addOnFailureListener { e ->
            showLoading(false)
            showError(parseFirebaseError(e))
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.progressOtp.isVisible = loading
        binding.btnVerifyOtp.isEnabled = !loading && isOtpComplete()
        etOtpFields.forEach { it.isEnabled = !loading }
        
        // Only enable resend if not loading AND timer has finished
        if (loading) {
            binding.btnResendOtp.isEnabled = false
        } else {
            // If timer is not null, it will re-enable itself on finish
            // So we don't force it to true here
        }
    }

    private fun showError(msg: String) {
        binding.tvOtpError.text = msg
        binding.tvOtpError.isVisible = msg.isNotEmpty()
    }

    private fun isOtpComplete(): Boolean {
        return etOtpFields.all { it.text.toString().length == 1 }
    }

    private fun updateVerifyButtonState() {
        binding.btnVerifyOtp.isEnabled = isOtpComplete()
    }

    private fun formatToPhilippines(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("63") -> "+$digits"
            digits.startsWith("0") -> "+63${digits.drop(1)}"
            else -> "+63$digits"
        }
    }

    private fun parseFirebaseError(e: Exception): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("invalid") || msg.contains("credential") || msg.contains("incorrect") ->
                "Invalid code. Please try again."
            msg.contains("too many requests") || msg.contains("blocked") ->
                "Too many attempts. Please wait a moment."
            msg.contains("quota") ->
                "SMS quota exceeded. Please try again later."
            msg.contains("authorized") || msg.contains("sha-1") || msg.contains("package") ->
                "App configuration error. Please contact support."
            msg.contains("network") ->
                "Network error. Please check your connection."
            else -> e.localizedMessage ?: "Verification failed. Please try again."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countDownTimer?.cancel()
        _binding = null
    }
}