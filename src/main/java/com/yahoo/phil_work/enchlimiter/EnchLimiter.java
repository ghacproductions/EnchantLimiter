/***
 * File EnchLimiter.java
 * 
 * History:
 *  27 Feb 2014 : New from Unbreakable.java
 *  09 Mar 2014 : Removed some debug messages
 *  09 Apr 2014 : Added Limit Multiples config and "Message on limit" config.
 *  10 Apr 2014 : Added new config for inhibited enchants
 *  22 Apr 2014 : Added "ALL" enchantment config, support disallowed on anvil.
 */

package com.yahoo.phil_work.enchlimiter;

import com.yahoo.phil_work.LanguageWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.*;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.Material;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginLogger;
import org.bukkit.scheduler.BukkitRunnable;

public class EnchLimiter extends JavaPlugin implements Listener {
	public Logger log;
	private LanguageWrapper language;
    public String chatName;

	boolean hasAndDeductXP (final Player player, int levels) {
		if (player.getGameMode() == org.bukkit.GameMode.CREATIVE)
			return true;
			
		if (player.getLevel() < levels) {
			player.sendMessage (language.get (player, "needXP", chatName + ": Insufficient XP"));
			return false;
		}  
		else {
			player.setLevel (player.getLevel() - levels);
			return true;
		}	
	}

	@EventHandler (ignoreCancelled = true)
	void enchantMonitor (EnchantItemEvent event) {
		Player player = event.getEnchanter();
		boolean limitMultiples = getConfig().getBoolean ("Limit Multiples", true) && 
			! player.hasPermission ("enchlimiter.multiple");
		
		int enchants = 0;
		ItemStack item = event.getItem();
		
		// Get List of disallowed enchants for that item and for ALL items
		Map<Enchantment, Integer> disallowedEnchants = getDisallowedEnchants (item.getType());
		disallowedEnchants.putAll (getDisallowedEnchants (Material.AIR));
		//*DEBUG*/log.info ("disallowed on " + item.getType() + disallowedEnchants);
		
		if ( !limitMultiples && disallowedEnchants.isEmpty())
			return;  // nothing to do; leave event alive for another plugin
				
		Map<Enchantment,Integer> toAdd = new HashMap<Enchantment, Integer>();
		toAdd.putAll (event.getEnchantsToAdd()); // to avoid modifying while iterating
		for (Enchantment ench : toAdd.keySet()) {
			int level = toAdd.get(ench);
			if ( !limitMultiples || enchants == 0) {
				if (disallowedEnchants.containsKey (ench) && level >= disallowedEnchants.get (ench) &&
					! player.hasPermission ("enchlimiter.disallowed") ) 
				{
					event.setExpLevelCost (event.getExpLevelCost() - level);
					event.getEnchantsToAdd().remove (ench);

					if (getConfig().getBoolean ("Message on disallowed", true))
						player.sendMessage (language.get (player, "disallowed", 
											chatName + ": removed disallowed enchant {0}-{1}", ench.getName(), level ));
				}
				else {
					enchants++;
					//item.addEnchantment (ench, level);
					//*DEBUG*/log.info ("Added " + ench + "-" + level + " to " +item.getType());
				}
			} else {
				event.setExpLevelCost (event.getExpLevelCost() - level);
				event.getEnchantsToAdd().remove (ench);
				if (getConfig().getBoolean ("Message on limit", true))
					player.sendMessage (language.get (player, "limited", 
										chatName + ": removed multiple enchant {0}", ench.getName() ));
			}
		} 

		if (enchants == 0)  {
			//log.info ("Removed all enchants attempted on " + item.getType());
			event.setCancelled (true);
		}
	}

	//  Listen to PrepareItemCraftEvent and return one of the books 
	@EventHandler (ignoreCancelled = true)
	void craftMonitor (InventoryClickEvent event) {
		ItemStack book = null;
		ItemStack tool = null;
		Inventory inv = event.getInventory();
		boolean limitMultiples = getConfig().getBoolean ("Limit Multiples", true);
	
		// log.info ("InventoryClickEvent " +event.getAction()+" in type " + inv.getType() + " in  slot " + event.getRawSlot() + "(raw " + event.getSlot());
		InventoryAction action = event.getAction();
		boolean isPlace = false;
		switch (action) {
			case PLACE_ALL:
			case PLACE_SOME:
			case PLACE_ONE:
			case SWAP_WITH_CURSOR:
			case MOVE_TO_OTHER_INVENTORY: // could be.. 
				isPlace = true;
				break;
			default:
				break;
		}
	
		if (inv.getType()== InventoryType.ANVIL && event.getSlotType() == SlotType.CRAFTING) {
			ItemStack[] anvilContents = inv.getContents();
			ItemStack slot0 = anvilContents[0];
			ItemStack slot1 = anvilContents[1];
			
			if (isPlace) {
				//log.info ("Placed a " + event.getCursor() + " in slot " + event.getRawSlot());
				//log.info ("currentItem: " +  event.getCurrentItem());
				if (event.getRawSlot() == 1)
					slot1 = event.getCursor();
				else if (event.getRawSlot() == 0)
					slot0 = event.getCursor();
			}	
			// 1 is right slot of anvil
			if (slot1 != null && slot1.getType() == Material.ENCHANTED_BOOK)
				book = slot1;
			// 0 is left slot of Anvil
			if (slot0 != null /* && slot0.getType() == Material.ENCHANTED_BOOK */)
				tool = slot0;
			// log.info ("Found book: " + book + "; tool: " + tool);
		}
		if (book != null && tool != null && isPlace)
		{ // then might be using a book with another book
			Map<Enchantment, Integer> disallowedEnchants = getDisallowedEnchants (tool.getType());
			disallowedEnchants.putAll (getDisallowedEnchants (Material.AIR));
			boolean disallowed = false;
			
			ItemMeta meta = book.getItemMeta();
			if ( !(meta instanceof EnchantmentStorageMeta))
				log.warning ("Book without storage meta: " + book);
			else {
				EnchantmentStorageMeta bookStore = (EnchantmentStorageMeta)meta;
				for (Enchantment e: bookStore.getStoredEnchants().keySet()) {
					//*DEBUG*/log.info ("testing for " + e + "-" + bookStore.getStoredEnchantLevel(e));
					if (disallowedEnchants.containsKey (e) && 
						disallowedEnchants.get(e) <= bookStore.getStoredEnchantLevel(e) )
						disallowed = true;
					else { 
						//*DEBUG*/log.info ("Allowing enchant bcs disallowed=" + disallowedEnchants.get(e));
					}
				}
			}
			if (event.getWhoClicked().hasPermission ("enchlimiter.disallowed"))
				disallowed = false;
		
			if (disallowed || 
			    (limitMultiples && 
				 (book.getType() == Material.ENCHANTED_BOOK && tool.getType() == Material.ENCHANTED_BOOK) ) )
			{
				final HumanEntity human = event.getWhoClicked();
				final Player player = (Player)human; 
				final ItemStack slot0 = tool.clone(), slot1 = book;
				final boolean disallowedEntry = disallowed;
				
				if (!(human instanceof Player)) {
					log.warning (human + " clicked on anvil, not a Player");
					return;
				}
				else if (!disallowed && player.hasPermission ("enchlimiter.books")) {
					log.info (language.get (Bukkit.getConsoleSender(), "bookEnch", "Permitting {0} to enchant book+book", player.getName()));
					return;
				}
				
				class BookReturner extends BukkitRunnable {
					@Override
					public void run() {
						if ( !player.isOnline()) {
							log.info (language.get (player, "loggedoff", "{0} logged off before we could cancel anvil enchant", player.getName()));
							return;
						}
						if (player.getOpenInventory().getTopInventory().getType() != InventoryType.ANVIL) {
							//*DEBUG*/log.info (player.getName() + " closed inventory before enchant occurred");
							return;
						}
						AnvilInventory aInventory = (AnvilInventory)player.getOpenInventory().getTopInventory();
						InventoryView pInventory = player.getOpenInventory();

						// Make sure we should still do this, that anvil still ready
						boolean stop = false;
						if (aInventory.getItem (0) == null || !(aInventory.getItem (0).isSimilar (slot0))) {
							//*DEBUG*/log.info ("removed " + slot0.getType() + " before enchant occurred; instead found "+ aInventory.getItem (0));
							//*DEBUG*/log.info ("which is " + (!(aInventory.getItem (0).isSimilar (slot0)) ? "NOT ":"") + "similar to "+ slot0);
							stop = true;
						}
						if (aInventory.getItem (1) == null || !aInventory.getItem (1).isSimilar (slot1)) {
							//*DEBUG*/log.info ("removed book before enchant occurred");
							stop = true;
						}
						if (pInventory.getCursor() != null && pInventory.getCursor().getType() != Material.AIR) {
							//*DEBUG*/log.info ("Non empty cursor slot " + pInventory.getCursor().getType()  + " for enchant result");
							stop = true;
						}		
						if (stop) {
							// log.info (player.getName() + " already stopped book+book enchant");
							return;
						}
						// Execute cancel
						pInventory.setCursor (slot0); // return to user
						aInventory.clear(0); // remove  book from tool slot
						
						if (getConfig().getBoolean ("Message on cancel"))
							player.sendMessage (language.get (player, "cancelled", chatName + ": You don't have permission to do that"));

						if ( !disallowedEntry)
							log.info (language.get (Bukkit.getConsoleSender(), "attempted", "{0} just tried to do a book+book enchant", player.getName()));
						else
							log.info (language.get (Bukkit.getConsoleSender(), "attempted2", "{0} just tried to do a disallowed anvil enchant", player.getName()));
					}
				}
				if (player != null)
					(new BookReturner()).runTask(this);	
			}
		}
	}
		
	private void addNewLanguages () {
		final String pluginPath = "plugins"+ getDataFolder().separator + getDataFolder().getName() + getDataFolder().separator;

		try {  //iterate through added languages
			String classURL = this.getClass().getResource(this.getName()+".class").toString();
			String jarName = classURL.substring (classURL.lastIndexOf (':') + 1, classURL.indexOf ('!'));
			ZipInputStream jar = new ZipInputStream (new FileInputStream (jarName));
			if (jar != null) {
				ZipEntry e = jar.getNextEntry();
				while (e != null)   {
					String name = e.getName();
					if (name.startsWith ("languages/") && !new File (pluginPath + name).exists()) 
					{
						saveResource (name, false);
						log.info ("Adding language file: " + name);
					}
					e = jar.getNextEntry();
				}
			}
			else 
				log.warning ("Unable to open jar file");						
		} catch (Exception ex) {
			log.warning ("Unable to process language files: " + ex);		
		}	
	}		
	
	private Map<Enchantment, Integer> getDisallowedEnchants (Material m) 
	{
		HashMap<Enchantment, Integer> results = new HashMap<Enchantment, Integer>();
		String matString = (m == Material.AIR ? "ALL" : m.toString());

		if ( !getConfig().isConfigurationSection ("Disallowed enchants." + matString))
			return results; // an empty list rather than null

		for (String enchantString : getConfig().getConfigurationSection ("Disallowed enchants." + matString).getKeys (false)) {
			int level = getConfig().getInt ("Disallowed enchants." + matString + "." + enchantString);
			if (level < 1) {
				log.warning ("Unsupported " + matString + "." + enchantString + " enchant level: " + level);
				continue;
			}
			Enchantment enchant;
			if (enchantString.equals ("ALL")) {
				for (Enchantment e : Enchantment.values()) {
					results.put (e, level);
				}
			}		
			else if ((enchant = Enchantment.getByName (enchantString)) == null)
			// TODO: Allow for "All" enchantments
				log.warning ("Unknown enchantment: " + enchantString+ ". Refer to http://bit.ly/HxVS58");
			else {
				results.put (enchant, level);
			}
		}
		return results;
	}   
			
	public void onEnable()
	{
		log = this.getLogger();
		chatName = ChatColor.BLUE + this.getName() + ChatColor.RESET;
		language = new LanguageWrapper(this, "eng"); // English locale
		final String pluginPath = "plugins"+ getDataFolder().separator + getDataFolder().getName() + getDataFolder().separator;

		if ( !getDataFolder().exists() || !(new File (pluginPath + "config.yml").exists()) )
		{
			getConfig().options().copyDefaults(true);
			log.info ("No config found in " + pluginPath + "; writing defaults");
			saveDefaultConfig();
		} else {
			// Parse config for errors
			if (getConfig().isConfigurationSection ("Disallowed enchants")) 
			{
				for (String itemString : getConfig().getConfigurationSection ("Disallowed enchants").getKeys (false /*depth*/)) {
					Material m = Material.matchMaterial (itemString);
					if (itemString.equals("ALL"))
						m = Material.AIR;
						
					if (m == null)
						log.warning ("Unknown item: " + itemString + ". Refer to http://bit.ly/1hjNiY7");
					else if (m != Material.AIR && m.isBlock())
						log.warning ("Do not support blocks in 'Disallowed enchants': " + m);
					else {
						log.config ("Disallowed enchants." + (m==Material.AIR ? "ALL" : m) + ":" + getDisallowedEnchants (m));
						// getDisallowedEnchants (m); 	// just for error checking
					}
				}
			}
		}
		addNewLanguages();		
			
		getServer().getPluginManager().registerEvents ((Listener)this, this);
		log.info (language.get (Bukkit.getConsoleSender(), "enabled", "EnchantLimiter in force; by Filbert66"));
	}
	
	public void onDisable()
	{
	}
}