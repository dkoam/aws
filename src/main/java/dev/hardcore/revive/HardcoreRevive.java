package dev.hardcore.revive;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class HardcoreRevive extends JavaPlugin implements Listener, TabExecutor {

    /** 죽은 사람 (UUID -> 이름) */
    private final Map<UUID, String> dead = new LinkedHashMap<>();
    /** 부활 예약 (접속/리스폰하면 이 위치에서 부활) */
    private final Map<UUID, Location> pending = new HashMap<>();

    private File dataFile;
    private NamespacedKey bandageKey;
    private NamespacedKey targetKey;
    private NamespacedKey recipeKey;

    /** 부활 명단 GUI를 구분하기 위한 홀더 */
    private static class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bandageKey = new NamespacedKey(this, "bandage");
        targetKey = new NamespacedKey(this, "target");
        recipeKey = new NamespacedKey(this, "revive_bandage");

        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createBandage());
        recipe.shape("NNN", "NDN", "NNN");
        recipe.setIngredient('N', Material.NETHERITE_SCRAP);
        recipe.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(recipe);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("revive") != null) {
            getCommand("revive").setExecutor(this);
            getCommand("revive").setTabCompleter(this);
        }
        if (getCommand("reviveitem") != null) {
            getCommand("reviveitem").setExecutor(this);
            getCommand("reviveitem").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ───────────────────────── 데이터 저장/불러오기 ─────────────────────────

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection d = y.getConfigurationSection("dead");
        if (d != null) {
            for (String k : d.getKeys(false)) {
                try {
                    dead.put(UUID.fromString(k), d.getString(k, "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection p = y.getConfigurationSection("pending");
        if (p != null) {
            for (String k : p.getKeys(false)) {
                Location loc = p.getLocation(k);
                if (loc == null) continue;
                try {
                    pending.put(UUID.fromString(k), loc);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : dead.entrySet()) {
            y.set("dead." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Location> e : pending.entrySet()) {
            y.set("pending." + e.getKey(), e.getValue());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("data.yml 저장 실패: " + ex.getMessage());
        }
    }

    // ───────────────────────── 붕대 아이템 ─────────────────────────

    public ItemStack createBandage() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("부활 붕대", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("죽은 동료를 되살립니다", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("우클릭 또는 F키: 부활 명단 열기", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("인벤토리에 있으면 죽을 때 자동으로 부활", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(bandageKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBandage(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(bandageKey, PersistentDataType.BYTE);
    }

    private boolean hasBandage(Player p) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : inv.getStorageContents()) {
            if (isBandage(s)) return true;
        }
        return isBandage(inv.getItemInOffHand());
    }

    /** 인벤토리(핫바·가방)에서 먼저, 없으면 왼손에서 1개 소모 */
    private boolean consumeBandage(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (isBandage(s)) {
                if (s.getAmount() > 1) {
                    s.setAmount(s.getAmount() - 1);
                } else {
                    contents[i] = null;
                }
                inv.setStorageContents(contents);
                return true;
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (isBandage(off)) {
            if (off.getAmount() > 1) {
                off.setAmount(off.getAmount() - 1);
                inv.setItemInOffHand(off);
            } else {
                inv.setItemInOffHand(null);
            }
            return true;
        }
        return false;
    }

    // ───────────────────────── 사망 / 리스폰 / 접속 ─────────────────────────

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        dead.put(p.getUniqueId(), p.getName());
        pending.remove(p.getUniqueId());
        saveData();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Location loc = pending.remove(id);
        if (loc != null) {
            dead.remove(id);
            saveData();
            e.setRespawnLocation(loc);
            Bukkit.getScheduler().runTask(this, () -> applyRevive(p));
        } else if (dead.containsKey(id)) {
            Bukkit.getScheduler().runTask(this, () -> p.setGameMode(GameMode.SPECTATOR));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        p.discoverRecipe(recipeKey);
        Location loc = pending.remove(id);
        if (loc != null) {
            dead.remove(id);
            saveData();
            Bukkit.getScheduler().runTask(this, () -> {
                p.teleport(loc);
                applyRevive(p);
            });
        } else if (dead.containsKey(id) && !p.isDead()) {
            Bukkit.getScheduler().runTask(this, () -> p.setGameMode(GameMode.SPECTATOR));
        }
    }

    // ───────────────────────── 자가부활 (토템처럼 치명타를 막음) ─────────────────────────

    private static final Set<EntityDamageEvent.DamageCause> NO_SAVE = Set.of(
            EntityDamageEvent.DamageCause.VOID,
            EntityDamageEvent.DamageCause.KILL,
            EntityDamageEvent.DamageCause.SUICIDE
    );

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (NO_SAVE.contains(e.getCause())) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        if (!consumeBandage(p)) return;

        e.setCancelled(true);

        for (PotionEffect eff : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(eff.getType());
        }
        double hp = Math.min(maxHealth(p), getConfig().getDouble("self-revive-health", 10.0));
        p.setHealth(Math.max(1.0, hp));
        p.setFireTicks(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        reviveEffects(p);
        p.sendActionBar(Component.text("부활 붕대가 당신을 살렸습니다!", NamedTextColor.GOLD));
    }

    private double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 20.0;
    }

    private void reviveEffects(Player p) {
        Location l = p.getLocation();
        p.getWorld().playSound(l, Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 100, 0.5, 1.0, 0.5, 0.3);
    }

    // ───────────────────────── 다른 사람 부활 ─────────────────────────

    /** 부활 처리. 온라인이면 즉시, 오프라인/사망화면이면 접속·리스폰 때 부활. */
    private boolean reviveTarget(UUID id, Location where) {
        if (!dead.containsKey(id) || pending.containsKey(id)) return false;
        String name = dead.get(id);
        Player t = Bukkit.getPlayer(id);
        if (t != null && !t.isDead()) {
            dead.remove(id);
            saveData();
            t.teleport(where);
            applyRevive(t);
        } else {
            pending.put(id, where.clone());
            saveData();
            Bukkit.broadcast(Component.text(name + "님은 다음 접속(또는 리스폰) 때 부활합니다.", NamedTextColor.YELLOW));
        }
        return true;
    }

    private void applyRevive(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 3));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));
        reviveEffects(p);
        p.showTitle(Title.title(Component.text("부활!", NamedTextColor.GOLD), Component.empty()));
        Bukkit.broadcast(Component.text(p.getName() + "님이 부활했습니다!", NamedTextColor.GREEN));
    }

    // ───────────────────────── 명단 GUI ─────────────────────────

    private void openMenu(Player p) {
        List<Map.Entry<UUID, String>> list = new ArrayList<>();
        for (Map.Entry<UUID, String> e : dead.entrySet()) {
            if (!pending.containsKey(e.getKey())) list.add(e);
        }
        if (list.isEmpty()) {
            p.sendMessage(Component.text("지금 부활시킬 수 있는 사람이 없습니다.", NamedTextColor.GRAY));
            return;
        }
        int count = Math.min(list.size(), 54);
        int size = ((count - 1) / 9 + 1) * 9;
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("부활시킬 사람을 선택하세요"));
        holder.inventory = inv;
        for (int i = 0; i < count; i++) {
            UUID id = list.get(i).getKey();
            String name = list.get(i).getValue();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            sm.displayName(Component.text(name, NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            sm.lore(List.of(Component.text("클릭하면 부활!", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            sm.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, id.toString());
            head.setItemMeta(sm);
            inv.setItem(i, head);
        }
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isBandage(e.getItem())) return;
        e.setCancelled(true);
        openMenu(e.getPlayer());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isBandage(e.getOffHandItem())) {
            e.setCancelled(true);
            openMenu(e.getPlayer());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        String s = it.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (s == null) return;
        UUID id;
        try {
            id = UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return;
        }
        p.closeInventory();
        if (!hasBandage(p)) {
            p.sendMessage(Component.text("부활 붕대가 없습니다.", NamedTextColor.RED));
            return;
        }
        if (reviveTarget(id, p.getLocation())) {
            consumeBandage(p);
        } else {
            p.sendMessage(Component.text("이미 부활했거나 부활이 예약된 사람입니다.", NamedTextColor.RED));
        }
    }

    // ───────────────────────── 명령어 ─────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("reviveitem")) {
            Player target = null;
            if (args.length > 0) {
                target = Bukkit.getPlayerExact(args[0]);
            } else if (sender instanceof Player pl) {
                target = pl;
            }
            if (target == null) {
                sender.sendMessage(Component.text("대상 플레이어를 찾을 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            Map<Integer, ItemStack> left = target.getInventory().addItem(createBandage());
            for (ItemStack rest : left.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            }
            sender.sendMessage(Component.text(target.getName() + "님에게 부활 붕대를 지급했습니다.", NamedTextColor.GREEN));
            return true;
        }

        // /부활
        if (args.length == 0) {
            sender.sendMessage(Component.text("사용법: /부활 <이름|목록|전체>", NamedTextColor.YELLOW));
            return true;
        }
        Location where;
        if (sender instanceof Player pl) {
            where = pl.getLocation();
        } else {
            where = Bukkit.getWorlds().get(0).getSpawnLocation();
        }

        String arg = args[0];
        if (arg.equals("목록") || arg.equalsIgnoreCase("list")) {
            if (dead.isEmpty()) {
                sender.sendMessage(Component.text("죽은 사람이 없습니다.", NamedTextColor.GRAY));
            } else {
                sender.sendMessage(Component.text("죽은 사람: " + String.join(", ", dead.values()), NamedTextColor.RED));
            }
            return true;
        }
        if (arg.equals("전체") || arg.equalsIgnoreCase("all")) {
            int n = 0;
            for (UUID id : new ArrayList<>(dead.keySet())) {
                if (reviveTarget(id, where)) n++;
            }
            sender.sendMessage(Component.text(n + "명을 부활시켰습니다.", NamedTextColor.GREEN));
            return true;
        }
        UUID found = null;
        for (Map.Entry<UUID, String> e : dead.entrySet()) {
            if (e.getValue().equalsIgnoreCase(arg)) {
                found = e.getKey();
                break;
            }
        }
        if (found == null) {
            sender.sendMessage(Component.text("죽은 사람 중에 '" + arg + "' 님이 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (reviveTarget(found, where)) {
            sender.sendMessage(Component.text(arg + "님을 부활시켰습니다.", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("이미 부활이 예약된 사람입니다.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String prefix = args[0].toLowerCase();
        if (cmd.getName().equalsIgnoreCase("reviveitem")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
            }
            return out;
        }
        List<String> options = new ArrayList<>(dead.values());
        options.add("목록");
        options.add("전체");
        for (String o : options) {
            if (o.toLowerCase().startsWith(prefix)) out.add(o);
        }
        return out;
    }
}
