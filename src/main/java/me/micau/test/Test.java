package me.micau.test;

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

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

public class Test extends JavaPlugin {

    private ServerSocket serverSocket;
    private ExecutorService clientHandlingExecutor;
    private boolean running = false;

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

                    processDepthData(depthData);
                    processColorData(colorData);

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



    private byte[][][] convertToMatrix(byte[] data, int width, int height) {
        byte[][][] matrix = new byte[height][width][4]; // Corrected width and height

        for (int i = 0; i < data.length; i += 4) {
            int pixelIndex = i / 4;
            int x = pixelIndex % width;
            int y = pixelIndex / width;

            matrix[y][x][0] = data[i + 2]; // Blue
            matrix[y][x][1] = data[i + 1]; // Green
            matrix[y][x][2] = data[i];     // Red
            matrix[y][x][3] = data[i + 3]; // Alpha
        }
        return matrix;
    }
    private BufferedImage matrixToBufferedImage(byte[][][] matrix, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int b = matrix[y][x][0] & 0xFF;
                int g = matrix[y][x][1] & 0xFF;
                int r = matrix[y][x][2] & 0xFF;
                int a = matrix[y][x][3] & 0xFF;
                int rgba = (a << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, rgba);
            }
        }
        return image;
    }


    private byte[][][] resizeMatrix(byte[][][] matrix, int originalWidth, int originalHeight, int newWidth, int newHeight) {
        byte[][][] resizedMatrix = new byte[newHeight][newWidth][4]; // Assuming RGBA format

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

    private void processColorData(byte[] colorData) {
        new BukkitRunnable() {
            @Override
            public void run() {
                int originalWidth = 1920;
                int originalHeight = 1080;
                int newWidth = 512;
                int newHeight = 424;

                // Convertir les données de couleur en matrice
                byte[][][] colorMatrix = convertToMatrix(colorData, originalWidth, originalHeight);

                BufferedImage matrixImage = matrixToBufferedImage(colorMatrix, originalWidth, originalHeight);


                // Redimensionner la matrice de couleur
                byte[][][] resizedColorMatrix = resizeMatrix(colorMatrix, originalWidth, originalHeight, newWidth, newHeight);


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
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        if (resizedColorMatrix[y][x][0] >= 100) {
                            getServer().getWorld("world").getBlockAt(x, 120, y).setType(Material.BLACK_STAINED_GLASS);
                        } else {
                            getServer().getWorld("world").getBlockAt(x, 120, y).setType(Material.WHITE_STAINED_GLASS);
                        }
                    }
                }
            }
        }.runTask(this);
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



    private void processDepthData(byte[] depthData) {
        new BukkitRunnable() {
            @Override
            public void run() {
                int width = 512;
                int height = 424;
                setBorder();

                for (int i = 0; i < depthData.length; i++) {
                    // Convertir byte en int non signé et ajuster les valeurs de profondeur
                    depthData[i] = (byte) ((depthData[i] - 770) * 100 / 130);
                }

                int size = nearestPowerOf2(Math.max(width, height));

                // Perform FFT
                Complex[] fftData = performFFT(depthData, width, height);

                // Apply low-pass filter to frequencies
                Complex[] processedFftData;

                processedFftData = applyFilterToFrequencies(fftData, size, highPassFilter);

                // Perform inverse FFT
                byte[] processedData = performInverseFFT(processedFftData, width, height);


                setBlocks(processedData, width, height);

            }
        }.runTask(this);
    }

    private void setBlocks(byte[] processedData, int width, int height) {
        for (int i = 0; i < processedData.length; i++) {
            int x = i % width;
            int z = i / width;
            int y = 60 - processedData[i]/2;  // Assurez-vous que processedData[i] est traité correctement


            // Placer les blocs en conséquence
            for (int j = 0; j < 3; j++) {
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
        }

    }



    private int getAverageDepth(byte[] depthData, int x, int z, int width, int height) {
        int sumDepth = 0;
        int count = 0;
        int windowSize = 2; // This will create a 5x5 window

        for (int dx = -windowSize; dx <= windowSize; dx++) {
            for (int dz = -windowSize; dz <= windowSize; dz++) {
                int nx = x + dx;
                int nz = z + dz;

                if (nx >= 0 && nx < width && nz >= 0 && nz < height) {
                    sumDepth += depthData[nz * width + nx];
                    count++;
                }
            }
        }

        return count > 0 ? sumDepth / count : 0;
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
