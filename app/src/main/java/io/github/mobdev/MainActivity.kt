package io.github.mobdev

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DecimalFormat


class MainActivity : AppCompatActivity() {

    private lateinit var mathOperation: TextView
    private lateinit var resultText: TextView

    private var currentInput = ""
    private var firstNumber = 0.0
    private var operation = ""
    private var isNewOperation = true
    private var isResultDisplayed = false

    private val decimalFormat = DecimalFormat().apply {
        maximumFractionDigits = 10
        isGroupingUsed = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mathOperation = findViewById(R.id.math_operation)
        resultText = findViewById(R.id.result_text)

        disableSystemKeyboard()

        initButtons()

        if (savedInstanceState != null) {
            currentInput = savedInstanceState.getString("currentInput", "")
            firstNumber = savedInstanceState.getDouble("firstNumber", 0.0)
            operation = savedInstanceState.getString("operation", "")
            isNewOperation = savedInstanceState.getBoolean("isNewOperation", true)
            isResultDisplayed = savedInstanceState.getBoolean("isResultDisplayed", false)
            updateDisplay()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentInput", currentInput)
        outState.putDouble("firstNumber", firstNumber)
        outState.putString("operation", operation)
        outState.putBoolean("isNewOperation", isNewOperation)
        outState.putBoolean("isResultDisplayed", isResultDisplayed)
    }

    private fun initButtons() {
        // Цифры
        findViewById<TextView>(R.id.btn_0).setOnClickListener { appendNumber("0") }
        findViewById<TextView>(R.id.btn_1).setOnClickListener { appendNumber("1") }
        findViewById<TextView>(R.id.btn_2).setOnClickListener { appendNumber("2") }
        findViewById<TextView>(R.id.btn_3).setOnClickListener { appendNumber("3") }
        findViewById<TextView>(R.id.btn_4).setOnClickListener { appendNumber("4") }
        findViewById<TextView>(R.id.btn_5).setOnClickListener { appendNumber("5") }
        findViewById<TextView>(R.id.btn_6).setOnClickListener { appendNumber("6") }
        findViewById<TextView>(R.id.btn_7).setOnClickListener { appendNumber("7") }
        findViewById<TextView>(R.id.btn_8).setOnClickListener { appendNumber("8") }
        findViewById<TextView>(R.id.btn_9).setOnClickListener { appendNumber("9") }
        findViewById<TextView>(R.id.btn_dot).setOnClickListener { appendDot() }

        // Операции
        findViewById<TextView>(R.id.btn_plus).setOnClickListener { setOperation("+") }
        findViewById<TextView>(R.id.btn_minus).setOnClickListener { setOperation("-") }
        findViewById<TextView>(R.id.btn_mod).setOnClickListener { setOperation("*") }
        findViewById<TextView>(R.id.btn_sub).setOnClickListener { setOperation("/") }

        // Специальные кнопки
        findViewById<TextView>(R.id.btn_AC).setOnClickListener { clearAll() }
        findViewById<TextView>(R.id.btn_back).setOnClickListener { deleteLast() }
        findViewById<TextView>(R.id.btn_equals).setOnClickListener { calculate() }
    }

    private fun disableSystemKeyboard() {
        mathOperation.showSoftInputOnFocus = false
        mathOperation.isFocusable = false
        mathOperation.isFocusableInTouchMode = false

        resultText.showSoftInputOnFocus = false
        resultText.isFocusable = false
        resultText.isFocusableInTouchMode = false
    }


    private fun appendNumber(number: String) {
        // Если после результата начинаем новый ввод
        if (isResultDisplayed) {
            clearAll()
            isResultDisplayed = false
        }

        // Если новая операция, то очищаем текущий ввод
        if (isNewOperation) {
            currentInput = ""
            isNewOperation = false
        }

        // Ограничиваем длину
        if (currentInput.length < 15) {
            currentInput += number
            updateDisplay()
        }
    }

    private fun appendDot() {
        if (isResultDisplayed) {
            clearAll()
            isResultDisplayed = false
        }

        if (isNewOperation) {
            currentInput = ""
            isNewOperation = false
        }

        // Проверяем есть ли уже точка в текущем числе
        if (!currentInput.contains(".")) {
            currentInput += if (currentInput.isEmpty()) "0." else "."
            updateDisplay()
        }
    }

    private fun setOperation(op: String) {
        // Если после результата начинаем новую операцию
        if (isResultDisplayed) {
            firstNumber = resultText.text.toString().toDouble()
            isResultDisplayed = false
            currentInput = ""
            operation = op
            isNewOperation = true
            updateDisplay()
            return
        }

        // Если есть текущий ввод — сохраняем его
        if (currentInput.isNotEmpty()) {
            firstNumber = currentInput.toDouble()
            currentInput = ""
            operation = op
            isNewOperation = true
            updateDisplay()
        } else if (operation.isNotEmpty()) {
            // Меняем операцию если она уже была
            operation = op
            updateDisplay()
        }
    }

    private fun calculate() {
        // Если нет операции или нет второго числа
        if (operation.isEmpty()) return

        val secondNumber = if (currentInput.isNotEmpty()) {
            currentInput.toDouble()
        } else if (isResultDisplayed) {
            resultText.text.toString().toDouble()
        } else {
            return
        }

        val result = when (operation) {
            "+" -> firstNumber + secondNumber
            "-" -> firstNumber - secondNumber
            "*" -> firstNumber * secondNumber
            "/" -> {
                if (secondNumber == 0.0) {
                    mathOperation.text = "ERROR"
                    return
                }
                firstNumber / secondNumber
            }
            else -> return
        }

        // Форматируем результат
        val formattedResult = formatResult(result)
        resultText.text = formattedResult

        // Показываем выражение в верхнем поле
        val formattedFirst = formatResult(firstNumber)
        val formattedSecond = formatResult(secondNumber)
        mathOperation.text = "$formattedFirst $operation $formattedSecond ="

        // Сохраняем результат для дальнейших операций
        firstNumber = result
        currentInput = ""
        operation = ""
        isNewOperation = true
        isResultDisplayed = true
    }

    private fun deleteLast() {
        if (isResultDisplayed) {
            clearAll()
            return
        }

        if (currentInput.isNotEmpty()) {
            currentInput = currentInput.dropLast(1)
            updateDisplay()
        }
    }

    private fun clearAll() {
        currentInput = ""
        firstNumber = 0.0
        operation = ""
        isNewOperation = true
        isResultDisplayed = false
        mathOperation.text = ""
        resultText.text = "0"
    }

    private fun updateDisplay() {
        // Обновляем нижнее поле
        if (currentInput.isEmpty()) {
            resultText.text = if (operation.isEmpty()) "0" else "0"
        } else {
            resultText.text = currentInput
        }

        // Обновляем верхнее поле
        if (operation.isNotEmpty() && firstNumber != 0.0) {
            val formattedFirst = formatResult(firstNumber)
            mathOperation.text = "$formattedFirst $operation"
        } else if (operation.isEmpty() && mathOperation.text.toString().isNotEmpty() && !mathOperation.text.toString().contains("=")) {
            // Очищаем верхнее поле если нет операции
            mathOperation.text = ""
        }
    }

    private fun formatResult(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            // Ограничиваем количество знаков после запятой
            val formatted = decimalFormat.format(value)
            formatted.replace(",", ".")
        }
    }
}