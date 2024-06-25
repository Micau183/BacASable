package me.micau.test;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import org.bukkit.block.Block;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream inputStream = clientSocket.getInputStream()) {
                    byte[] tempBuffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(tempBuffer)) != -1) {
                        buffer.write(tempBuffer, 0, bytesRead);
                    }

                    byte[] depthData = buffer.toByteArray();
                    processDepthData(depthData);

                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    stopDepthDataReceiver();
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void processDepthData(byte[] depthData) {
        new BukkitRunnable() {
            @Override
            public void run() {
                int width = 512;
                int height = 424;
                getServer().getWorld("world").getBlockAt(0, 100, 0).setType(Material.RED_WOOL);
                getServer().getWorld("world").getBlockAt(width, 100, 0).setType(Material.RED_WOOL);
                getServer().getWorld("world").getBlockAt(width, 100, height).setType(Material.RED_WOOL);
                getServer().getWorld("world").getBlockAt(0, 100, height).setType(Material.RED_WOOL);

                for (int i = 0; i < depthData.length; i++) {
                    int x = i % width;
                    int z = i / width;
                    int y = getAverageDepth(depthData, x, z, width, height) & 0xFF; // Convert byte to a value between 0 and 255

                    if (x == 200 & z == 280){
                        System.out.println(y);
                        System.out.println(depthData[z * width + x]);
                    }

                    int adjustedY = 100 - (y / 2);
                    setBlock(x, adjustedY, z);
                    setBlock(x, adjustedY - 1, z);
                    setBlock(x, adjustedY - 2, z);
                }
            }
        }.runTask(this);
    }

    private void setBlock(int x, int y, int z){
        if (y < 60){
            getServer().getWorld("world").getBlockAt(x, y, z).setType(Material.GRASS_BLOCK);
        } else if (y < 85) {
            getServer().getWorld("world").getBlockAt(x, y, z).setType(Material.STONE);
        } else {
            getServer().getWorld("world").getBlockAt(x, y, z).setType(Material.SNOW_BLOCK);
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
                    sumDepth += depthData[nz * width + nx] & 0xFF;
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
