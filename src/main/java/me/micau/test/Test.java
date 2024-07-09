package me.micau.test;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import org.bukkit.block.Block;
import org.bukkit.TreeType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;


import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class Test extends JavaPlugin implements Listener  {

    private ServerSocket serverSocket;
    private ExecutorService clientHandlingExecutor;
    private boolean running = false;
    private static final int[] ROW_DIRECTIONS = {-1, 1, 0, 0};
    private static final int[] COL_DIRECTIONS = {0, 0, -1, 1};

    @Override
    public void onEnable() {
        getLogger().info("MyMinecraftPlugin est activé !");
        // Enregistrer les commandes et leurs gestionnaires
        this.getCommand("startdepth").setExecutor(new StartDepthCommand());
        this.getCommand("stopdepth").setExecutor(new StopDepthCommand());
        this.getCommand("reset").setExecutor(new ResetCommand());
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getLogger().info("MyMinecraftPlugin est désactivé !");
        stopDepthDataReceiver();
    }
    @EventHandler
    public void onBlockFlow(BlockFromToEvent event) {
        // Vérifie si le bloc source ou de destination est de l'eau
        if (event.getBlock().getType() == Material.WATER || event.getToBlock().getType() == Material.WATER) {
            // Annule l'événement pour empêcher l'eau de se déplacer
            event.setCancelled(true);
        }
    }

    private void startDepthDataReceiver() {
        if (running) {
            getLogger().warning("Le récepteur de données de profondeur est déjà en cours d'exécution.");
            return;
        }

        running = true;
        clientHandlingExecutor = Executors.newCachedThreadPool(); // Recréer l'ExecutorService

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(25566); // Assurez-vous que ce port est libre
                    while (running && !serverSocket.isClosed()) {
                        try {
                            Socket clientSocket = serverSocket.accept();
                            clientHandlingExecutor.submit(() -> handleClientSocket(clientSocket));
                        } catch (IOException e) {
                            if (running) {
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void stopDepthDataReceiver() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (clientHandlingExecutor != null) {
            clientHandlingExecutor.shutdownNow(); // Utilisez shutdownNow pour forcer l'arrêt immédiat des tâches en attente
        }
    }

    private void handleClientSocket(Socket clientSocket) {
        new BukkitRunnable() {
            @Override
            public void run() {
                ByteArrayOutputStream depthBuffer = new ByteArrayOutputStream();
                ByteArrayOutputStream colorBuffer = new ByteArrayOutputStream();

                try (InputStream inputStream = clientSocket.getInputStream()) {
                    byte[] tempBuffer = new byte[1024];
                    int bytesRead;
                    boolean readingDepthData = true;

                    while ((bytesRead = inputStream.read(tempBuffer)) != -1) {
                        if (readingDepthData) {
                            depthBuffer.write(tempBuffer, 0, bytesRead);
                            if (depthBuffer.size() >= 217088) {
                                readingDepthData = false; // Finished reading depth data
                            }
                        } else {
                            colorBuffer.write(tempBuffer, 0, bytesRead);
                            if (colorBuffer.size() >= 8294400) {
                                break; // Finished reading color data
                            }
                        }
                    }

                    byte[] depthData = depthBuffer.toByteArray();
                    byte[] colorData = colorBuffer.toByteArray();

                    processDepthDataConv(depthData, colorData);

                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    stopDepthDataReceiver();
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void setBorder() {
        int width = 512;
        int height = 424;
        getServer().getWorld("world").getBlockAt(0, 100, 0).setType(Material.RED_WOOL);
        getServer().getWorld("world").getBlockAt(width, 100, 0).setType(Material.RED_WOOL);
        getServer().getWorld("world").getBlockAt(width, 100, height).setType(Material.RED_WOOL);
        getServer().getWorld("world").getBlockAt(0, 100, height).setType(Material.RED_WOOL);
    }



    private int[][][] convertToMatrix(byte[] data, int width, int height) {
        int[][][] matrix = new int[height][width][4]; // Corrected width and height

        for (int i = 0; i < data.length; i += 4) {
            int pixelIndex = i / 4;
            int x = pixelIndex % width;
            int y = pixelIndex / width;

            // Ensure byte to int conversion handles negative values correctly
            matrix[y][x][0] = data[i] & 0xFF; // Blue
            matrix[y][x][1] = data[i + 1] & 0xFF; // Green
            matrix[y][x][2] = data[i + 2] & 0xFF; // Red
            matrix[y][x][3] = data[i + 3] & 0xFF; // Alpha
        }
        return matrix;
    }

    private BufferedImage matrixToBufferedImage(int[][][] matrix, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int b = matrix[y][x][0];
                int g = matrix[y][x][1];
                int r = matrix[y][x][2];
                int a = matrix[y][x][3];
                int rgba = (a << 24) | (b << 16) | (g << 8) | r;
                image.setRGB(x, y, rgba);
            }
        }
        return image;
    }

    private int[][][] resizeMatrix(int[][][] matrix, int originalWidth, int originalHeight, int newWidth, int newHeight) {
        int[][][] resizedMatrix = new int[newHeight][newWidth][4]; // Assuming RGBA format
        float ratioH = 0.338F;
        float ratioW = 0.3422F;
        float cstH = -27F;
        float cstW = 101F;

        int newX;
        int newY;

        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                newX = (int) ((x + cstW) * (1 / ratioW));
                newY = (int) ((y + cstH) * (1 / ratioH)); // Corrected from (x + cstH)

                // Check bounds before accessing the original matrix
                if (newX >= 0 && newX < originalWidth && newY >= 0 && newY < originalHeight) {
                    resizedMatrix[y][x] = matrix[newY][newX];
                } else {
                    // Handle the case when newX or newY is out of bounds
                    resizedMatrix[y][x] = new int[] {255, 255, 255, 255}; // Default white color for out-of-bounds
                }
            }
        }

        return resizedMatrix;
    }

    private void saveImage(BufferedImage image, String filePath) throws IOException {
        File file = new File(filePath);
        ImageIO.write(image, "png", file);
    }

    private int[][][] detectCouleur(int[][][] image, int width, int height) {
        int[][] zoneBleue = new int[height][width];
        int[][] zoneVerte = new int[height][width];
        int[][] zoneRouge = new int[height][width]; // Assuming RGBA format

        for (int x = 0; x < height; x++) {
            for (int y = 0; y < width; y++) {
                int red = image[x][y][0];
                int green = image[x][y][1];
                int blue = image[x][y][2];

                if (blue > 25 + green && blue > 25 + red) {
                    zoneBleue[x][y] = 1; // Detected blue
                    zoneRouge[x][y] = 0;
                    zoneVerte[x][y] = 0;
                } else if (red > 25 + green && red > 25 + blue) {
                    zoneRouge[x][y] = 1; // Detected red
                    zoneBleue[x][y] = 0;
                    zoneVerte[x][y] = 0;
                } else if (green > 10 + red && green > 10 + blue) {
                    zoneVerte[x][y] = 1; // Detected green
                    zoneBleue[x][y] = 0;
                    zoneRouge[x][y] = 0;
                } else {
                    zoneBleue[x][y] = 0;
                    zoneRouge[x][y] = 0;
                    zoneVerte[x][y] = 0; // No color detected
                }
            }
        }

        return new int[][][]{zoneRouge, zoneVerte, zoneBleue};
    }


    public static boolean checkVoisin(int[][] matrix, int i, int j, int c) {
        int rows = matrix.length;
        int cols = matrix[0].length;

        // Check each direction while ensuring we don't go out of bounds
        if (i + c < rows && matrix[i + c][j] == 0) return true;
        if (i - c >= 0 && matrix[i - c][j] == 0) return true;
        if (j + c < cols && matrix[i][j + c] == 0) return true;
        if (j - c >= 0 && matrix[i][j - c] == 0) return true;
        if (i + c < rows && j + c < cols && matrix[i + c][j + c] == 0) return true;
        if (i + c < rows && j - c >= 0 && matrix[i + c][j - c] == 0) return true;
        if (i - c >= 0 && j + c < cols && matrix[i - c][j + c] == 0) return true;
        if (i - c >= 0 && j - c >= 0 && matrix[i - c][j - c] == 0) return true;

        return false;
    }
    public static int[][] processMatrix(int[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
            throw new IllegalArgumentException("Matrix cannot be null or empty");
        }

        int rows = matrix.length;
        int cols = matrix[0].length;
        int[][] resultMatrix = new int[rows][cols];

        // Parcourir chaque élément de la matrice
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                // Compter les voisins non nuls
                int nonZeroNeighbors = countNonZeroNeighbors(matrix, i, j);

                // Si l'élément a au moins 3 voisins non nuls, le copier dans la nouvelle matrice
                if (nonZeroNeighbors >= 4) {
                    resultMatrix[i][j] = matrix[i][j];
                } else {
                    resultMatrix[i][j] = 0;
                }
            }
        }

        return resultMatrix;
    }

    private static int countNonZeroNeighbors(int[][] matrix, int row, int col) {
        int count = 0;
        int rows = matrix.length;
        int cols = matrix[0].length;

        // Vérifier les 8 voisins possibles
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) {
                    continue; // Ignorer l'élément central
                }

                int neighborRow = row + i;
                int neighborCol = col + j;

                // Vérifier que le voisin est dans les limites de la matrice
                if (neighborRow >= 0 && neighborRow < rows && neighborCol >= 0 && neighborCol < cols) {
                    if (matrix[neighborRow][neighborCol] != 0) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    public static int[][] detectEau(int[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (matrix[i][j] != 0) {
                    int compteur = 1;
                    while (true) {
                        if (checkVoisin(matrix, i, j, compteur)) {
                            matrix[i][j] = compteur;
                            break;
                        } else {
                            compteur++;
                        }
                    }
                }
            }
        }
        return matrix;
    }
    public static int[][] placeArbre(int[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;

        double rand;

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (matrix[i][j] != 0) {
                    rand = Math.random();
                    if (rand < 0.998){
                        matrix[i][j] = 0;
                    }

                }
            }
        }
        return matrix;
    }

    public static void saveImage(float[][] matrix) {
        int height = matrix.length;
        int width = matrix[0].length;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Remplir l'image avec les valeurs de la matrice
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (int)matrix[y][x]);
            }
        }

        // Sauvegarder l'image en tant que fichier PNG
        try {
            File outputfile = new File("depth.png");
            ImageIO.write(image, "png", outputfile);
            System.out.println("Image saved successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private int[][][] getColorData(byte[] colorData) {

                int originalWidth = 1920;
                int originalHeight = 1080;
                int newWidth = 512;
                int newHeight = 424;

                // Convertir les données de couleur en matrice
                assert (1920*1080*4 == colorData.length);

                int[][][] colorMatrix = convertToMatrix(colorData, originalWidth, originalHeight);

                BufferedImage matrixImage = matrixToBufferedImage(colorMatrix, originalWidth, originalHeight);

                // Redimensionner la matrice de couleur



                int[][][] resizedColorMatrix = resizeMatrix(colorMatrix, originalWidth, originalHeight, newWidth, newHeight);

                int[][][] zone_couleur= detectCouleur(resizedColorMatrix, newWidth, newHeight);



                int[][] zoneBleue = processMatrix(processMatrix(detectEau(zone_couleur[2])));
                int[][] zoneVerte = placeArbre(zone_couleur[1]);
                int[][] zoneRouge = zone_couleur[0];




            BufferedImage image = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);

        // Remplir l'image avec les valeurs de la matrice
        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                int value = zoneBleue[y][x] == 1 ? 0xFFFFFF : 0x000000; // Blanc pour 1, noir pour 0
                image.setRGB(x, y, value);
            }
        }

        // Sauvegarder l'image en tant que fichier PNG
        try {
            File outputfile = new File("bleu.png");
            ImageIO.write(image, "png", outputfile);
            System.out.println("Image saved successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }


                // Convertir la matrice redimensionnée en BufferedImage
                BufferedImage resizedImage = matrixToBufferedImage(resizedColorMatrix, newWidth, newHeight);

                try {
                    // Enregistrer l'image redimensionnée
                    saveImage(resizedImage, "resized_image.png");
                    saveImage(matrixImage, "matrixImage.png");
                } catch (IOException e) {
                    e.printStackTrace();
                }

            return new int[][][]{zoneRouge, zoneVerte, zoneBleue};


    }
















    private int nearestPowerOf2(int size) {
        int pow = 1;
        while (pow < size) {
            pow *= 2;
        }
        return pow;
    }

    private Complex[] performFFT(byte[] depthData, int width, int height) {
        // Determine the nearest power of 2 size for FFT
        int size = nearestPowerOf2(Math.max(width, height));

        // Prepare the complex array for FFT
        Complex[] fftData = new Complex[size * size];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                fftData[y * size + x] = new Complex(depthData[y * width + x] & 0xFF, 0);
            }
        }
        for (int i = height * size; i < fftData.length; i++) {
            fftData[i] = Complex.ZERO;
        }

        // Apply FFT
        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
        return fft.transform(fftData, TransformType.FORWARD);
    }

    @FunctionalInterface
    public interface FrequencyFilter {
        Complex apply(double distance, Complex value, int size);
    }

    FrequencyFilter lowPassFilter = (distance, value, size) -> {
        double cutoffFrequency = 0.1; // Example cutoff frequency
        return distance > cutoffFrequency * size ? Complex.ZERO : value;
    };

    FrequencyFilter highPassFilter = (distance, value, size) -> {
        double cutoffFrequency = 0.1; // Example cutoff frequency
        return distance <= cutoffFrequency * size ? Complex.ZERO : value;
    };

    FrequencyFilter bandPassFilter = (distance, value, size) -> {
        double lowCutoffFrequency = 0.1;
        double highCutoffFrequency = 0.3;
        return (distance > lowCutoffFrequency * size && distance < highCutoffFrequency * size) ? value : Complex.ZERO;
    };

    FrequencyFilter bandStopFilter = (distance, value, size) -> {
        double lowCutoffFrequency = 0.1;
        double highCutoffFrequency = 0.3;
        return (distance < lowCutoffFrequency * size || distance > highCutoffFrequency * size) ? value : Complex.ZERO;
    };

    FrequencyFilter gaussianFilter = (distance, value, size) -> {
        double sigma = 0.2;
        double gaussian = Math.exp(-0.5 * Math.pow(distance / (sigma * size), 2));
        return value.multiply(gaussian);
    };

    FrequencyFilter softHighPassFilter = (distance, value, size) -> {
        double cutoffFrequency = 0.1;
        double attenuation = 1 - Math.exp(-distance / (cutoffFrequency * size));
        return value.multiply(attenuation);
    };

    FrequencyFilter softLowPassFilter = (distance, value, size) -> {
        double cutoffFrequency = 0.1;
        double attenuation = Math.exp(-distance / (cutoffFrequency * size));
        return value.multiply(attenuation);
    };

    private Complex[] applyFilterToFrequencies(Complex[] fftData, int size, FrequencyFilter filter) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.sqrt(x * x + y * y);
                fftData[y * size + x] = filter.apply(distance, fftData[y * size + x], size);
            }
        }
        return fftData;
    }


    private byte[] performInverseFFT(Complex[] fftData, int width, int height) {
        // Apply inverse FFT
        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] ifftData = fft.transform(fftData, TransformType.INVERSE);

        // Extract the filtered depth data
        byte[] filteredData = new byte[width * height];
        int size = nearestPowerOf2(Math.max(width, height));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                filteredData[y * width + x] = (byte) Math.min(Math.max(ifftData[y * size + x].getReal(), 0), 255);
            }
        }
        return filteredData;
    }



//    private void processDepthDataFft(byte[] depthData) {
//        new BukkitRunnable() {
//            @Override
//            public void run() {
//                int width = 512;
//                int height = 424;
//                setBorder();
//
//                for (int i = 0; i < depthData.length; i++) {
//                    // Convertir byte en int non signé et ajuster les valeurs de profondeur
//                    depthData[i] = (byte) ((depthData[i] - 770) * 100 / 130);
//                }
//
//
//
//                int size = nearestPowerOf2(Math.max(width, height));
//
//                // Perform FFT
//                Complex[] fftData = performFFT(depthData, width, height);
//
//                // Apply low-pass filter to frequencies
//                Complex[] processedFftData;
//
//                processedFftData = applyFilterToFrequencies(fftData, size, lowPassFilter);
//
//                // Perform inverse FFT
//                byte[] processedData = performInverseFFT(processedFftData, width, height);
//
//
//                setBlocks(processedData, width, height);
//
//            }
//        }.runTask(this);
//    }

    @FunctionalInterface
    public interface KernelFilter {
        float[][] size(int size);
    }


    KernelFilter blurKernel = size -> {
        float[][] kernel = new float[size][size];
        float value = 1.0f / (size * size);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                kernel[i][j] = value;
            }
        }
        return kernel;
    };

    KernelFilter edgeKernel = size -> {
        if (size == 3) {
            return new float[][] {
                    {-1, -1, -1},
                    {-1,  8, -1},
                    {-1, -1, -1}
            };
        } else if (size == 5) {
            return new float[][] {
                    { 0 / 10f, -1 / 10f, -1 / 10f, -1 / 10f,  0 / 10f },
                    {-1 / 10f,  2 / 10f,  2 / 10f,  2 / 10f, -1 / 10f },
                    {-1 / 10f,  2 / 10f,  8 / 10f,  2 / 10f, -1 / 10f },
                    {-1 / 10f,  2 / 10f,  2 / 10f,  2 / 10f, -1 / 10f },
                    { 0 / 10f, -1 / 10f, -1 / 10f, -1 / 10f,  0 / 10f }
            };
        } else {
            throw new IllegalArgumentException("Edge detection kernel size must be 3 or 5.");
        }
    };

    public static float[][] sobelXKernel() {
        return new float[][] {
                {-1/6f,  0,  1/6f},
                {-2/6f,  0,  2/6f},
                {-1/6f,  0,  1/6f}
        };
    }
    public static float[][] sobelYKernel() {
        return new float[][] {
                {-1/6f, -2/6f, -1/6f},
                { 0,  0,  0},
                { 1/6f,  2/6f,  1/6f}
        };
    }



    private void processDepthDataConv(byte[] depthData, byte[] colorData) {
        new BukkitRunnable() {
            @Override
            public void run() {
                int width = 512;
                int height = 424;
                setBorder();

                // Convert byte data to 2D array of floats
                float[][] image = convertTo2DArray(depthData, width, height);

                float[][] processedDepthMap;

                // Apply convolution filter
                processedDepthMap = applyConvolution(image, blurKernel.size(5), width, height);

                saveImage(processedDepthMap);
                // Convert the result back to 1D byte array
                byte[] DepthMap = convertTo1DArray(processedDepthMap, width, height);

                float[][] processedHeightMapX = applyConvolution(image, sobelXKernel(), width, height);
                float[][] processedHeightMapY = applyConvolution(image, sobelYKernel(), width, height);



                float[][] processedHeightMap = sumAbsoluteValues( processedHeightMapX, processedHeightMapY);

                byte[] HeightMap = convertTo1DArray(processedHeightMap, width, height);


                int[][][] colorMatrix = getColorData(colorData);
                byte[] RedMap = convertTo1DArray(colorMatrix[0], width, height);
                byte[] GreenMap = convertTo1DArray(colorMatrix[1], width, height);
                byte[] BlueMap = convertTo1DArray(colorMatrix[2], width, height);

                //getAverageWater(colorMap, DepthMap);


                setBlocks(DepthMap, HeightMap, width,  BlueMap, GreenMap);
            }
        }.runTask(this);
    }
    public static float[][] sumAbsoluteValues(float[][] matrix1, float[][] matrix2) {
        // Vérifier que les deux matrices ont la même taille
        if (matrix1.length != matrix2.length || matrix1[0].length != matrix2[0].length) {
            throw new IllegalArgumentException("Les matrices doivent avoir la même taille.");
        }

        int rows = matrix1.length;
        int cols = matrix1[0].length;
        float[][] result = new float[rows][cols];

        // Parcourir chaque élément des matrices
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = Math.abs(matrix1[i][j]) + Math.abs(matrix2[i][j]);
            }
        }

        return result;
    }


    // Converts byte array to 2D float array
    private float[][] convertTo2DArray(byte[] depthData, int width, int height) {
        float[][] image = new float[height][width];
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                image[i][j] = (depthData[i * width + j]); // Convert byte to unsigned int
            }
        }
        return image;
    }

    // Applies a convolution filter to a 2D float array with mirror padding
    private float[][] applyConvolution(float[][] image, float[][] kernel, int width, int height) {
        int kernelHeight = kernel.length;
        int kernelWidth = kernel[0].length;
        int padY = kernelHeight / 2; // Calculate padding size (top and bottom)
        int padX = kernelWidth / 2;  // Calculate padding size (left and right)

        // Initialize result matrix with the same size as the input image
        float[][] result = new float[height][width];

        // Iterate over each pixel in the image
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0.0f;

                // Apply the kernel to the surrounding pixels
                for (int ky = -padY; ky <= padY; ky++) {
                    for (int kx = -padX; kx <= padX; kx++) {
                        // Reflect the coordinates
                        int pixelY = y + ky;
                        int pixelX = x + kx;

                        // Handle boundary conditions with mirror padding
                        if (pixelY < 0) {
                            pixelY = -pixelY; // Reflect the top boundary
                        } else if (pixelY >= height) {
                            pixelY = 2 * height - pixelY - 2; // Reflect the bottom boundary
                        }

                        if (pixelX < 0) {
                            pixelX = -pixelX; // Reflect the left boundary
                        } else if (pixelX >= width) {
                            pixelX = 2 * width - pixelX - 2; // Reflect the right boundary
                        }

                        sum += image[pixelY][pixelX] * kernel[ky + padY][kx + padX];
                    }
                }
                result[y][x] = sum;
            }
        }

        return result;
    }



    // Converts 2D float array back to 1D byte array
    private byte[] convertTo1DArray(float[][] result, int width, int height) {
        // Initialize the 1D byte array with the size of width * height
        byte[] depthData = new byte[width * height];

        // Convert 2D float array to 1D byte array
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                // Ensure the values are clamped between Byte.MIN_VALUE and Byte.MAX_VALUE
                depthData[i * width + j] = (byte) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, result[i][j]));
            }
        }

        return depthData;
    }
    private byte[] convertTo1DArray(int[][] result, int width, int height) {
        // Initialize the 1D byte array with the size of width * height
        byte[] depthData = new byte[width * height];

        // Convert 2D float array to 1D byte array
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                // Ensure the values are clamped between Byte.MIN_VALUE and Byte.MAX_VALUE
                depthData[i * width + j] = (byte) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, result[i][j]));
            }
        }

        return depthData;
    }

    public int getAverageWater(byte[] colorMap, byte[] depthMap) {
        // Calcul pour la première moitié du tableau
        int sum1 = 0;
        int counter1 = 0;
        for (int i = 0; i < colorMap.length; i++) {
            if (colorMap[i] != 0) {
                sum1 += depthMap[i];

                counter1++;
            }
        }

        int w1 = (counter1 == 0) ? 50 : sum1 / counter1;

        return w1;
    }
    public int getLowerWater(byte[] colorMap, byte[] depthMap) {
        // Calcul pour la première moitié du tableau
        int mini = 0;
        for (int i = 0; i < colorMap.length; i++) {
            if (colorMap[i] != 0) {
                if  (depthMap[i] > mini){
                    mini = depthMap[i];
                }
            }
        }
        return mini;
    }


    private void setBlocks(byte[] processedDepthData, byte[] processedHeightData,int width, byte[] BlueMap, byte[] GreenMap) {
        for (int i = 0; i < processedDepthData.length; i++) {
            int x = i % width;
            int z = i / width;
            int y = 70 - processedDepthData[i]/2;  // Assurez-vous que processedData[i] est traité correctement

            double rand;

            // Le terrain
            for (int j = 0; j < 2 + 2*Math.abs(processedHeightData[i]); j++) {
                int currentY = y - j;
                Material material;

                Random random = new Random();

                double gaussian = random.nextGaussian();

                double factor = 1.5; // Ajuster ce facteur pour obtenir la distribution souhaitée
                int randomNumber = (int) Math.max(-5, Math.min(5, Math.round(gaussian * factor)));

                int Y_block = randomNumber + currentY;


                if (Y_block < 60) {

                    material = Material.GRASS_BLOCK;
                    rand = Math.random();

                    if (rand < 0.003 ) {
                        Block block = getServer().getWorld("world").getBlockAt(x, currentY, z);
                        block.applyBoneMeal(BlockFace.UP);
                    }


                } else if (Y_block < 85) {
                    material = Material.STONE;
                } else {
                    material = Material.SNOW_BLOCK;
                }

                getServer().getWorld("world").getBlockAt(x, currentY, z).setType(material);


            }

            // La flotte

            if(BlueMap[i] != 0){
                getServer().getWorld("world").getBlockAt(x, y -((int)(2*Math.sqrt(BlueMap[i]))), z).setType(Material.GRAVEL);
                getServer().getWorld("world").getBlockAt(x, y -((int)(2*Math.sqrt(BlueMap[i])))-1, z).setType(Material.STONE);

                for (int j =0 ; j <(int)(2*Math.sqrt(BlueMap[i])); j++){

                   // if ((waterlvl < processedDepthData[i] -j +2)) {
                        getServer().getWorld("world").getBlockAt(x, y - j, z).setType(Material.WATER);

               }
            }
            if(GreenMap[i] == 1){
                Location location = new Location(Bukkit.getWorlds().get(0), x, y+1, z);
                location.getWorld().generateTree(location, TreeType.BIRCH);

            }
        }

    }




    private void resetBlocksAround(Player player, int radius) {
        int centerX = player.getLocation().getBlockX();
        int centerY = player.getLocation().getBlockY();
        int centerZ = player.getLocation().getBlockZ();

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int y = centerY - radius; y <= centerY + radius; y++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    Block block = player.getWorld().getBlockAt(x, y, z);
                    if (block.getType() != Material.AIR) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
    }

    public class ResetCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                int radius = 300; // Default radius

                if (args.length > 0) {
                    try {
                        radius = Integer.parseInt(args[0]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("Le rayon spécifié n'est pas un nombre valide.");
                        return false;
                    }
                }

                player.sendMessage("Réinitialisation des blocs autour de vous avec un rayon de " + radius + " blocs...");
                resetBlocksAround(player, radius);
                return true;
            } else {
                sender.sendMessage("Cette commande ne peut être exécutée que par un joueur.");
                return false;
            }
        }
    }

    // CommandExecutor pour la commande /startdepth
    public class StartDepthCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                player.sendMessage("Réception des données de profondeur Kinect démarrée.");
                startDepthDataReceiver();
                return true;
            }
            return false;
        }
    }

    // CommandExecutor pour la commande /stopdepth
    public class StopDepthCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                player.sendMessage("Arrêt de la réception des données de profondeur Kinect.");
                stopDepthDataReceiver();
                return true;
            }
            return false;
        }
    }
}
