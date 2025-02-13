package com.example.handd;


import java.io.File;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.core.Preview;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Arrays;

import java.util.concurrent.ExecutionException;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;

import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.tensorflow.lite.Interpreter;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.io.FileInputStream;

public class CameraActivity extends AppCompatActivity {

    private ImageCapture imageCapture;
    public ImageView photoPreview;
    TextView editText;
    Button sendButton, takeButton, newButton;
    View previewView;

    private Interpreter tflite;

    private MappedByteBuffer loadModelFile() throws Exception {
        FileInputStream inputStream = new FileInputStream(getAssets().openFd("emnist.tflite").getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = getAssets().openFd("emnist.tflite").getStartOffset();
        long declaredLength = getAssets().openFd("emnist.tflite").getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        try {
            tflite = new Interpreter(loadModelFile());
            Toast.makeText(this, "Model Yüklendi", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Model Yüklenemedi", Toast.LENGTH_SHORT).show();
        }

        checkPermissions();
        startCamera();

        photoPreview = findViewById(R.id.photoPreview);
        previewView = findViewById(R.id.previewView);
        sendButton = findViewById(R.id.button_send);
        takeButton = findViewById(R.id.button_take_photo);
        newButton = findViewById(R.id.button_new_photo);
        editText = findViewById(R.id.editText);

        takeButton.setOnClickListener(v -> capturePhoto());
        newButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, CameraActivity.class);
            startActivity(intent);
        });

    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();

                PreviewView previewView = findViewById(R.id.previewView);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.i("hata", String.valueOf(e));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capturePhoto() {

        if (imageCapture == null) {
            Toast.makeText(this, "Kamera henüz başlatılmadı.", Toast.LENGTH_SHORT).show();
            return;
        }

        File photoFile = new File(getExternalFilesDir(null), "photo.jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        runOnUiThread(() -> {
                            Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());

                            photoPreview.setImageBitmap(bitmap);
                            previewView.setVisibility(View.GONE);
                            takeButton.setVisibility(View.GONE);


                            photoPreview.setVisibility(View.VISIBLE);
                            sendButton.setVisibility(View.VISIBLE);
                            newButton.setVisibility(View.VISIBLE);

                            sendButton.setOnClickListener(v -> makePrediction(bitmap));

                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(CameraActivity.this, "Fotoğraf çekme hatası: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });

    }


    private Bitmap preprocessBitmap(Bitmap originalBitmap) {
        // 28x28 boyutuna yeniden boyutlandır
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, 28, 28, true);
        //printBitmapValues(resizedBitmap);
        // Gri tonlamaya çevir
        Bitmap grayBitmap = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888);

        for (int y = 0; y < 28; y++) {
            for (int x = 0; x < 28; x++) {
                int pixel = resizedBitmap.getPixel(x, y);

                // Renk bileşenlerini al
                int red = (pixel >> 16) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = pixel & 0xFF;


                // Gri tonlama değeri hesapla
                int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                gray = 255 - gray;

                // Yeni gri piksel oluştur
                int newPixel = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
                grayBitmap.setPixel(x, y, newPixel);
            }
        }

        return grayBitmap;
    }


    private float[][][][] prepareInput(Bitmap bitmap) {
        float[][][][] input = new float[1][28][28][1];

        // Normalize edilmiş piksel değerlerini doldur
        for (int i = 0; i < 28; i++) {
            for (int j = 0; j < 28; j++) {
                int pixel = bitmap.getPixel(i, j);
                // Log.d("BitmapPixels", String.valueOf(pixel));


                float grayValue = (pixel & 0xFF) / 255.0f; // Gri tonlama
                // Log.d("BitmapPixels", String.valueOf(grayValue));


                input[0][i][j][0] = grayValue;

            }
        }


        return input;
    }


    private void makePrediction(Bitmap bitmap) {
        try {

            // Ön işleme yap
            Bitmap preprocessedBitmap = preprocessBitmap(bitmap);

            float[][][][] input = prepareInput(preprocessedBitmap);


            // Çıkış tahmini için boş bir dizi oluştur
            float[][] output = new float[1][47];
            tflite.run(input, output);
            Log.d("ModelOutput", Arrays.toString(output[0]));


            // Çıktıdaki maksimum değerin indeksini al (tahmin edilen sınıf)
            int predictedClass = getMaxIndex(output[0]);
            char predictedChar = mapToCharacter(predictedClass);

            editText.setVisibility(View.VISIBLE);
            editText.setText(String.valueOf(predictedChar));
            photoPreview.setImageBitmap(preprocessedBitmap);

        } catch (Exception e) {
            Log.e("TFLite", "Tahmin hatası: " + e.getMessage());
        }
    }


    private Integer getMaxIndex(float[] output) {
        int maxIndex = 0;
        float maxValue = output[0];
        for (int i = 1; i < output.length; i++) {
            if (output[i] > maxValue) {
                maxValue = output[i];
                maxIndex = i;
            }
        }
        // Eğer en büyük değer eşik değerden küçükse null döndür
        float threshold = 0.6f; // Eşik değer
        if (maxValue < threshold) {
            Toast.makeText(this, "Algılanamadı, tekrar deneyin", Toast.LENGTH_SHORT).show();
            return null;
        }
        return maxIndex;
    }

    private static final SparseArray<Character> MAPPING = new SparseArray<>() {{
        put(0, (char) 48);  // '0'
        put(1, (char) 49);  // '1'
        put(2, (char) 50);  // '2'
        put(3, (char) 51);  // '3'
        put(4, (char) 52);  // '4'
        put(5, (char) 53);  // '5'
        put(6, (char) 54);  // '6'
        put(7, (char) 55);  // '7'
        put(8, (char) 56);  // '8'
        put(9, (char) 57);  // '9'
        put(10, (char) 65); // 'A'
        put(11, (char) 66); // 'B'
        put(12, (char) 67); // 'C'
        put(13, (char) 68); // 'D'
        put(14, (char) 69); // 'E'
        put(15, (char) 70); // 'F'
        put(16, (char) 71); // 'G'
        put(17, (char) 72); // 'H'
        put(18, (char) 73); // 'I'
        put(19, (char) 74); // 'J'
        put(20, (char) 75); // 'K'
        put(21, (char) 76); // 'L'
        put(22, (char) 77); // 'M'
        put(23, (char) 78); // 'N'
        put(24, (char) 79); // 'O'
        put(25, (char) 80); // 'P'
        put(26, (char) 81); // 'Q'
        put(27, (char) 82); // 'R'
        put(28, (char) 83); // 'S'
        put(29, (char) 84); // 'T'
        put(30, (char) 85); // 'U'
        put(31, (char) 86); // 'V'
        put(32, (char) 87); // 'W'
        put(33, (char) 88); // 'X'
        put(34, (char) 89); // 'Y'
        put(35, (char) 90); // 'Z'
        put(36, (char) 97); // 'a'
        put(37, (char) 98); // 'b'
        put(38, (char) 100); // 'd'
        put(39, (char) 101); // 'e'
        put(40, (char) 102); // 'f'
        put(41, (char) 103); // 'g'
        put(42, (char) 104); // 'h'
        put(43, (char) 110); // 'n'
        put(44, (char) 113); // 'q'
        put(45, (char) 114); // 'r'
        put(46, (char) 116); // 't'
    }};

    private char mapToCharacter(int predictedClass) {
        Character mappedChar = MAPPING.get(predictedClass);
        if (mappedChar != null) {
            return mappedChar;
        } else {
            throw new IllegalArgumentException("Mapping bulunamadı: " + predictedClass);
        }
    }


    public void printBitmapValues(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Bitmap'in her pikselini al
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Pikselin rengini al (ARGB formatında)
                int pixel = bitmap.getPixel(x, y);

                // Pikselin gri seviyesini al (RGB bileşenleri aynı olduğu için herhangi birini alabilirsiniz)
                int gray = (pixel & 0xff); // RGB bileşenlerinin her biri aynı olur

                // Pikselin gri seviyesini yazdır
                System.out.print(gray + " ");
            }
            System.out.println(); // Her satır için bir satır boşluk bırak
        }
    }


}
