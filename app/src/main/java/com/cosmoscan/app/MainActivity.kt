package com.cosmoscan.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = IngredientRepository(this)

        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) {
                    repository.ensureDatabasePrepopulated()
                }
                CosmoScanApp(repository)
            }
        }
    }
}

@Composable
fun CosmoScanApp(repository: IngredientRepository) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        ScannerAndAnalysisScreen(repository)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Разрешить доступ к камере для сканирования этикеток")
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun ScannerAndAnalysisScreen(repository: IngredientRepository) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var capturedText by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<AnalysisReport?>(null) }

    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    if (isScanning) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                recognizer.process(image)
                                    .addOnSuccessListener { visionText ->
                                        if (visionText.text.length > 15) {
                                            capturedText = visionText.text
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            Log.e("CosmoScan", "Ошибка камеры", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (capturedText.isBlank())
                        "Наведите камеру на состав (Ingredients / Состав)"
                    else "Текст этикетки захвачен (${capturedText.length} симв.)",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        isScanning = false
                        isLoading = true
                        scope.launch {
                            val names = IngredientAnalyzer.extractIngredientNames(capturedText)
                            val resolved = names.map { repository.getOrFetchIngredient(it) }
                            report = IngredientAnalyzer.evaluateProduct(resolved)
                            isLoading = false
                        }
                    },
                    enabled = capturedText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Анализировать состав", fontSize = 16.sp)
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Результат проверки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    report = null
                    isScanning = true
                }) {
                    Text("Новое сканирование")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Сверяем с базой и запрашиваем научные статьи по API...")
                    }
                }
            } else {
                report?.let { res ->
                    val bannerColor = when {
                        res.hasHarmful -> Color(0xFFFFEBEE)
                        res.isIneffectiveFormula -> Color(0xFFFFF8E1)
                        else -> Color(0xFFE8F5E9)
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bannerColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Оценка качества: ${res.qualityScore} / 100",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = res.verdictTitle, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = res.verdictSummary, fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(res.ingredients, key = { it.inciName }) { item ->
                            IngredientCard(
                                item = item,
                                onAttachArticle = { newArticle ->
                                    scope.launch {
                                        val updatedItem = repository.attachStudyToIngredient(
                                            inciName = item.inciName,
                                            article = newArticle
                                        )
                                        if (updatedItem != null && report != null) {
                                            val updatedList = report!!.ingredients.map {
                                                if (it.inciName == updatedItem.inciName) updatedItem else it
                                            }
                                            report = report!!.copy(ingredients = updatedList)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IngredientCard(
    item: IngredientEntity,
    onAttachArticle: (ResearchArticle) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var showAttachDialog by remember { mutableStateOf(false) }

    val badgeColor = when (item.safety) {
        "SAFE" -> Color(0xFF2E7D32)
        "CAUTION" -> Color(0xFFEF6C00)
        "HARMFUL" -> Color(0xFFC62828)
        else -> Color(0xFF616161)
    }
    val badgeLabel = when (item.safety) {
        "SAFE" -> "Безопасно"
        "CAUTION" -> "С осторожностью"
        "HARMFUL" -> "Вредный / Агрессивный"
        else -> "Неизвестно"
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${item.ruName} (${item.inciName})",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = badgeLabel,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "Категория: ${item.category} • База: ${item.source}",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = item.properties, fontSize = 14.sp)

            if (item.articles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Научная база и исследования (${item.articles.size}):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1565C0)
                )
                Spacer(modifier = Modifier.height(4.dp))

                item.articles.forEach { article ->
                    Surface(
                        color = Color(0xFFF1F6FC),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable {
                                val safeUrl = if (article.url.startsWith("http")) {
                                    article.url
                                } else {
                                    "https://${article.url}"
                                }
                                runCatching { uriHandler.openUri(safeUrl) }
                            }
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "${article.authority} • ${article.year}" +
                                        if (article.isUserAdded) " [Добавлено вами]" else "",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0D47A1)
                            )
                            Text(
                                text = article.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline,
                                color = Color(0xFF1565C0)
                            )
                            if (article.summary.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = article.summary,
                                    fontSize = 12.sp,
                                    color = Color.DarkGray
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { showAttachDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("+ Прикрепить статью / исследование", fontSize = 12.sp)
                }
            }
        }
    }

    if (showAttachDialog) {
        AttachArticleDialog(
            ingredientName = item.ruName,
            onDismiss = { showAttachDialog = false },
            onConfirm = { article ->
                onAttachArticle(article)
                showAttachDialog = false
            }
        )
    }
}

@Composable
fun AttachArticleDialog(
    ingredientName: String,
    onDismiss: () -> Unit,
    onConfirm: (ResearchArticle) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var authority by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("2026") }
    var summary by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Прикрепить исследование: $ingredientName",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название статьи или отчёта *") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = authority,
                    onValueChange = { authority = it },
                    label = { Text("Источник (дерматолог, PubMed, ВОЗ, CIR) *") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text("Год публикации") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Ссылка (URL / DOI) *") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Краткий вывод исследования") },
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        ResearchArticle(
                            title = title.trim(),
                            authority = authority.trim(),
                            year = year.trim().ifBlank { "н/д" },
                            summary = summary.trim(),
                            url = url.trim(),
                            isUserAdded = true
                        )
                    )
                },
                enabled = title.isNotBlank() && authority.isNotBlank() && url.isNotBlank()
            ) {
                Text("Сохранить в базу")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
