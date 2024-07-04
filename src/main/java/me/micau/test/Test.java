package me.micau.test;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import org.bukkit.block.Block;

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
import java.util.LinkedList;
import java.util.Queue;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

public class Test extends JavaPlugin {

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
    }

    @Override
    public void onDisable() {
        getLogger().info("MyMinecraftPlugin est désactivé !");
        stopDepthDataReceiver();
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

        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                int srcX = x * originalWidth / newWidth;
                int srcY = y * originalHeight / newHeight;
                resizedMatrix[y][x] = matrix[srcY][srcX];
            }
        }

        return resizedMatrix;
    }

    private void saveImage(BufferedImage image, String filePath) throws IOException {
        File file = new File(filePath);
        ImageIO.write(image, "png", file);
    }

    private int[][] detectCouleur(int[][][] image, int width, int height) {
        int[][] zoneCouleur = new int[height][width]; // Assuming RGBA format

        for (int x = 0; x < height; x++) {
            for (int y = 0; y < width; y++) {
                int red = image[x][y][0];
                int green = image[x][y][1];
                int blue = image[x][y][2];

                if (blue > green  && blue > red ) {
                    zoneCouleur[x][y] = 1; // Detected blue
                } else if (red > 140 && green < 100 && blue < 100) {
                    zoneCouleur[x][y] = 2; // Detected red
                } else if (green > 140 && red < 130 && blue < 130) {
                    zoneCouleur[x][y] = 3; // Detected green
                } else {
                    zoneCouleur[x][y] = 0; // No color detected
                }
            }
        }

        return zoneCouleur;
    }

    private static boolean isValid(int row, int col, int rows, int cols) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    public static int[][] detectEau(int[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;

        Queue<int[]> queue = new LinkedList<>();
        int[][] distances = new int[rows][cols];

        // Initialize distances and queue with all the 0's
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (matrix[i][j] == 0) {
                    queue.add(new int[]{i, j});
                } else {
                    distances[i][j] = Integer.MAX_VALUE;
                }
            }
        }

        // Perform BFS from all 0's simultaneously
        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int currentRow = current[0];
            int currentCol = current[1];

            for (int k = 0; k < 4; k++) {
                int newRow = currentRow + ROW_DIRECTIONS[k];
                int newCol = currentCol + COL_DIRECTIONS[k];

                if (isValid(newRow, newCol, rows, cols) && matrix[newRow][newCol] == 1 && distances[newRow][newCol] == Integer.MAX_VALUE) {
                    distances[newRow][newCol] = distances[currentRow][currentCol] + 1;
                    queue.add(new int[]{newRow, newCol});
                }
            }
        }


        // Update the original matrix with the distances
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (matrix[i][j] == 1) {
                    matrix[i][j] = distances[i][j];
                }
            }
        }

        return matrix;
    }


    private int[][] getColorData(byte[] colorData) {

                int originalWidth = 1920;
                int originalHeight = 1080;
                int newWidth = 512;
                int newHeight = 424;

                // Convertir les données de couleur en matrice
                int[][][] colorMatrix = convertToMatrix(colorData, originalWidth, originalHeight);

                BufferedImage matrixImage = matrixToBufferedImage(colorMatrix, originalWidth, originalHeight);

                // Redimensionner la matrice de couleur
                int[][][] resizedColorMatrix = resizeMatrix(colorMatrix, originalWidth, originalHeight, newWidth, newHeight);

                int[][] zone_couleur= detectCouleur(resizedColorMatrix, newWidth, newHeight);

                int[][] zoneBleu = detectEau(zone_couleur);
                // Convertir la matrice redimensionnée en BufferedImage
                BufferedImage resizedImage = matrixToBufferedImage(resizedColorMatrix, newWidth, newHeight);

                try {
                    // Enregistrer l'image redimensionnée
                    saveImage(resizedImage, "resized_image.png");
                    saveImage(matrixImage, "matrixImage.png");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Traiter la matrice redimensionnée
//                for (int y = 0; y < newHeight; y++) {
//                    for (int x = 0; x < newWidth; x++) {
//                        if (zoneBleu[y][x] == 1) {
//                            getServer().getWorld("world").getBlockAt(x, 120, y).setType(Material.BLUE_STAINED_GLASS);
//                        } else if (zoneBleu[y][x] == 2) {
//                            getServer().getWorld("world").getBlockAt(x, 120, y).setType(Material.RED_STAINED_GLASS);
//                        } else if (zoneBleu[y][x] == 3) {
//                            getServer().getWorld("world").getBlockAt(x, 120, y).setType(Material.GREEN_STAINED_GLASS);
//                        } else{
//                            getServer().getWorld("world").getBlockAt(x, 120, y).setType(Material.WHITE_STAINED_GLASS);
//                        }
//                    }
            return zoneBleu;


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

                // Convert the result back to 1D byte array
                byte[] DepthMap = convertTo1DArray(processedDepthMap, width, height);

                float[][] processedHeightMapX = applyConvolution(image, sobelXKernel(), width, height);
                float[][] processedHeightMapY = applyConvolution(image, sobelYKernel(), width, height);



                float[][] processedHeightMap = sumAbsoluteValues( processedHeightMapX, processedHeightMapY);

                byte[] HeightMap = convertTo1DArray(processedHeightMap, width, height);


                int[][] colorMatrix = getColorData(colorData);
                byte[] colorMap = convertTo1DArray(colorMatrix, width, height);
                int waterlvl = getAverageWater(colorMap, HeightMap);

                setBlocks(DepthMap, HeightMap, width, waterlvl,  colorMap);
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

    private int getAverageWater (byte[] colorMap, byte[] depthMap){
        int sum = 0;
        int counter = 0;
        for (int i = 0; i< colorMap.length; i++){
            if (colorMap[i] != 0){
                sum += depthMap[i];
                counter += 1;
            }
        }
        if (counter == 0){
            return 50;
        }else{
        return (int) sum/counter;
    }}

    private void setBlocks(byte[] processedDepthData, byte[] processedHeightData,int width, int waterlvl, byte[] colorMap) {
        for (int i = 0; i < processedDepthData.length; i++) {
            int x = i % width;
            int z = i / width;
            int y = 90 - processedDepthData[i];  // Assurez-vous que processedData[i] est traité correctement



            // Placer les blocs en conséquence
            for (int j = 0; j < 2 + 2*Math.abs(processedHeightData[i]); j++) {
                int currentY = y - j;
                Material material;

                if (currentY < 60) {
                    material = Material.GRASS_BLOCK;
                } else if (currentY < 85) {
                    material = Material.STONE;
                } else {
                    material = Material.SNOW_BLOCK;
                }

                getServer().getWorld("world").getBlockAt(x, currentY, z).setType(material);


            }
            if(colorMap[i] != 0){
                for (int j = 0; j < processedDepthData[i] - colorMap[i]; j++){
                    int currentY = y - j;

                    getServer().getWorld("world").getBlockAt(x, currentY, z).setType(Material.WATER);

                }
                int currentY = y - (processedDepthData[i] - colorMap[i] + 1);
                Material material;

                if (currentY < 60) {
                    material = Material.GRASS_BLOCK;
                } else if (currentY < 85) {
                    material = Material.STONE;
                } else {
                    material = Material.SNOW_BLOCK;
                }

                getServer().getWorld("world").getBlockAt(x, currentY, z).setType(material);



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
