package planagochi;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.Scanner;

import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The whole game: rendering, input, timing and saving.
 *
 * Everything is drawn by hand as 30x30 grids of filled rectangles - there are no
 * image assets. The screen currently shown is selected by the "state" field:
 *
 *   0  tend the current pot (water / harvest)   7  customer: order accepted
 *   1  seed shop (3x3 grid)                     8  customer: thank you
 *   2  "you earned $N" interstitial             9  the room (walk around)
 *   4  character customization                 10  fishing minigame
 *   5  upgrade shop                            11  fishing: caught it
 *   6  customer: places an order               12  tutorial overlay
 */
public class GamePage extends JPanel implements ActionListener, MouseListener, MouseMotionListener, KeyListener {

	private static final long serialVersionUID = 1L;

	// ---------------- T U N I N G ----------------

	static final int POT_COUNT = 3;
	static final int MAX_HYDRATION = 6;
	static final int SECONDS_PER_STAGE = 60; // base seconds each growth stage takes
	static final int UPGRADE_BASE_COST = 50; // rises with every upgrade bought
	static final int CUSTOMER_BONUS = 50; // paid on top of the sale price
	static final int CUSTOMER_PATIENCE = 600; // seconds before an unserved customer is replaced
	static final int WITHER_SECONDS = 600; // seconds a growing plant survives with no water
	static final int TICK_MS = 500; // clock speed; plant logic runs every other tick
	static final int AUTOSAVE_SECONDS = 60;
	static final int MAX_OFFLINE_SECONDS = 24 * 60 * 60; // cap on catch-up when reopening

	// Fishing. Fill has to beat drain, but slowly enough that a catch is not free.
	static final int FISH_REWARD = 10;
	static final int FISH_FILL = 4; // catch meter gain per tick while the rod covers the fish
	static final int FISH_DRAIN = 3; // loss per tick otherwise
	static final int FISH_TURN_TICKS = 4; // how long the fish holds a direction
	static final int ROD_SINK = 18; // rod fall per tick
	static final int ROD_LIFT = 26; // rod rise per button press

	static final String SAVE_FILE = "SaveProg.ronasave";
	static final int SAVE_REQUIRED = 25; // values every save has, including pre-existing ones
	static final int SAVE_VALUES = 30; // values a save written today has

	// ---------------- A S S E T S ----------------

	Random random = new Random();
	ArrayList<Plant> plants = new ArrayList<Plant>(); // the 9 crops and their prices
	ArrayList<features> bodyParts = new ArrayList<features>(); // 5 hair, 5 shirts, 5 eyes, 5 mouths
	ArrayList<features> icons = new ArrayList<features>(); // glyphs drawn on the three buttons
	ArrayList<features> furnitureSprites = new ArrayList<features>(); // room tiles
	ArrayList<features> fishSprites = new ArrayList<features>();
	Font uiFont; // resolved once, not rebuilt on every repaint

	// ---------------- T H E   R O O M ----------------

	/** roomTiles[row][column], holding the tile ids named below. 0 is walkable floor. */
	int roomTiles[][] = new int[5][9];
	String tileNames[] = { "none", "plant", "closet", "upgrade booth", "invis", "customer", "invis", "invis", "invis",
			"p1", "p2", "p3", "Fishing Pond" };
	int nearbyTile; // interactive tile next to the player, 0 if nothing

	// ---------------- C O L O R S ----------------

	Color darkGray = new Color(50, 50, 50);
	Color lightGray = new Color(150, 150, 150);
	Color backgroundColor = new Color(100, 100, 100);
	Color screenBackColor = new Color(200, 200, 200);
	Color screenFrontColor = new Color(90, 90, 90);
	Color buttonYellow = new Color(232, 186, 86);
	Color buttonGreen = new Color(107, 189, 92);
	Color buttonRed = new Color(227, 82, 75);
	Color buttonYellowDark = new Color(200, 150, 60); // icon colors, one per button
	Color buttonGreenDark = new Color(90, 160, 70);
	Color buttonRedDark = new Color(210, 60, 50);
	Color fadeOverlay = new Color(60, 60, 60, 190); // dims the room behind the tutorial

	// ---------------- U I   S T A T E ----------------

	int state = 12; // screen currently shown; see the table above
	int tutorialStep = 0; // 0-5, step of the tutorial overlay
	int shopSelection = 0; // 0-8, highlighted seed in the shop
	int bodyPartSelection = 0; // 0-3, row being edited in customization
	int upgradeSelection = 0; // 0-5, highlighted upgrade
	boolean alwaysOnTop = true;
	MainPage parentFrame;
	int screenX = 200; // left edge of the main display area
	boolean slowTick = true; // flips every tick so plant logic runs at 1Hz
	Point dragOrigin; // set while dragging the undecorated window

	// ---------------- S A V E D   S T A T E ----------------

	// player look
	int playerHair = 0;
	int playerShirt = 0;
	int playerEyes = 0;
	int playerMouth = 0;
	// the customer standing in the shop, and the crop they want
	int customerHair = 0;
	int customerShirt = 0;
	int customerEyes = 0;
	int customerMouth = 0;
	int customerWantsPlant = 0;
	// gameplay
	int currentPot = 0; // 0-2, pot shown on screen 0
	int hydration[] = { MAX_HYDRATION, MAX_HYDRATION, MAX_HYDRATION };
	int potPlant[] = { 0, 0, 0 }; // which crop is growing in each pot
	int growthSecondsLeft[] = { 0, 0, 0 };
	boolean potWithered[] = { false, false, false }; // left dry too long; needs a new seed
	int money = 0;
	// upgrades, each costing upgradeCost()
	int upgradeDehydrateTime = 0; // extra seconds between water losses
	int upgradeDehydrateChance = 0; // % chance to skip a water loss
	int upgradeDoubleMoney = 0; // % chance a harvest pays double
	int upgradeFreeSeedChance = 0; // % chance a seed costs nothing
	int upgradeGrowthSpeed = 0; // seconds shaved off each growth stage
	int upgradeInstantGrowChance = 0; // % chance a seed grows instantly
	int upgradesBought = 0; // drives the rising upgrade price

	// ---------------- D E R I V E D   S T A T E ----------------

	int growthFrame[] = { 0, 0, 0 }; // sprite frame currently drawn for each pot
	int secondsUntilDehydrate[] = { SECONDS_PER_STAGE, SECONDS_PER_STAGE, SECONDS_PER_STAGE };
	int secondsDry[] = { 0, 0, 0 }; // how long each pot has sat with no water
	int dehydrateInterval = SECONDS_PER_STAGE;
	int lastSaleAmount = 0; // shown on the "you earned $N" screen
	boolean customerOrderFilled = false;
	int customerPatience = CUSTOMER_PATIENCE;
	int secondsSinceSave = 0;

	// ---------------- P L A Y E R   P O S I T I O N ----------------

	int playerCol = 4; // 0-8
	int playerRow = 2; // 0-4

	// ---------------- F I S H I N G ----------------

	int rodPos = 50; // height of the rod above the bottom of the track
	int rodHeight = 90;
	int fishPos = 100; // 20-260, the fish's position in the track
	int fishSpeed = 10;
	int fishMoveDir = 1; // 0 = up, 1 = hold, 2 = down
	int fishTurnCountdown = 0; // ticks until the fish picks a new direction
	int catchProgress = 0; // 0-100
	int caughtFishSprite;

	// =====================================================================

	GamePage(MainPage parent) {
		parentFrame = parent;
		this.setFocusable(true);
		this.setBackground(backgroundColor);
		this.addMouseMotionListener(this);
		this.addMouseListener(this);
		this.setPreferredSize(new Dimension(950, 320));
		setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
		this.requestFocus();
		this.addKeyListener(this);
		this.setLayout(null);
		uiFont = pickFont();

		// Assets first, then the save file, so loaded indexes can be range-checked
		loadPlants();
		loadBodyParts();
		loadIcons();
		loadFurniture();
		loadFish();
		loadProgress();
		setupRoom();

		// A Swing Timer fires on the event dispatch thread, so the clock and the
		// painting code never touch the game state at the same time.
		new Timer(TICK_MS, this).start();
	}

	/** Picks the first installed font from the list, so it isn't silently substituted. */
	private Font pickFont() {
		String installed[] = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		for (String wanted : new String[] { "Century Schoolbook L", "Century Schoolbook", "Bookman Old Style" }) {
			for (String have : installed) {
				if (have.equalsIgnoreCase(wanted)) {
					return new Font(have, Font.ITALIC, 16);
				}
			}
		}
		return new Font(Font.SERIF, Font.ITALIC, 16);
	}

	// ---------------- C L O C K ----------------

	/** Called by the Swing Timer every TICK_MS. */
	@Override
	public void actionPerformed(ActionEvent e) {
		slowTick = !slowTick;
		if (state == 10) {
			updateFishing(); // fishing runs at the full tick rate
		}
		if (slowTick) { // everything below is per second
			tickPlants(true);
			if (--customerPatience <= 0) {
				randomizeCustomer(); // never leave the player stuck on one order
			}
			if (++secondsSinceSave >= AUTOSAVE_SECONDS) {
				saveProgress();
			}
		}
		repaint();
	}

	/**
	 * Advances hydration and growth for all three pots by one second.
	 * Withering is skipped while catching up on offline time, so closing the game
	 * overnight costs you progress but never your crops.
	 */
	private void tickPlants(boolean allowWither) {
		if (plants.isEmpty()) {
			return;
		}
		for (int pot = 0; pot < POT_COUNT; pot++) {
			if (potWithered[pot]) {
				continue;
			}
			if (growthSecondsLeft[pot] <= 0) {
				growthFrame[pot] = plants.get(potPlant[pot]).frameCount - 1; // grown, and safe to leave
				secondsDry[pot] = 0;
				continue;
			}
			if (hydration[pot] <= 0) {
				secondsDry[pot]++;
				if (allowWither && secondsDry[pot] >= WITHER_SECONDS) {
					potWithered[pot] = true;
				}
				continue; // too dry: growth pauses
			}
			secondsDry[pot] = 0;
			// Count down to the next water loss; the upgrade gives a chance to skip it
			if (secondsUntilDehydrate[pot] > 0) {
				secondsUntilDehydrate[pot]--;
			} else {
				if (random.nextInt(100) >= upgradeDehydrateChance) {
					hydration[pot]--;
				}
				secondsUntilDehydrate[pot] = dehydrateInterval + upgradeDehydrateTime;
			}
			growthSecondsLeft[pot]--;
			growthFrame[pot] = frameForTimeLeft(pot);
		}
	}

	/** Seconds one growth stage takes, never zero so the division below is safe. */
	private int secondsPerStage() {
		return Math.max(1, SECONDS_PER_STAGE - upgradeGrowthSpeed);
	}

	/** Picks the sprite frame matching how much growing time is left in a pot. */
	private int frameForTimeLeft(int pot) {
		int lastFrame = plants.get(potPlant[pot]).frameCount - 1;
		if (growthSecondsLeft[pot] <= 0) {
			return lastFrame;
		}
		return Math.max(lastFrame - 1 - (growthSecondsLeft[pot] / secondsPerStage()), 0);
	}

	private boolean isReadyToHarvest(int pot) {
		return !plants.isEmpty() && !potWithered[pot] && growthSecondsLeft[pot] <= 0;
	}

	/** One step of the fishing minigame: move the fish, sink the rod, score the overlap. */
	private void updateFishing() {
		// The fish holds a direction for a few ticks, so it can actually be tracked
		if (--fishTurnCountdown <= 0) {
			fishMoveDir = random.nextInt(3);
			fishSpeed = random.nextInt(20) + 10;
			fishTurnCountdown = FISH_TURN_TICKS;
		}

		// Rod covering the fish fills the catch meter, otherwise it drains
		if (fishPos > 299 - rodPos - rodHeight && fishPos + 30 <= 300 - rodPos) {
			catchProgress += FISH_FILL;
			if (catchProgress >= 100) {
				catchProgress = 100;
				money += FISH_REWARD;
				state = 11;
				saveProgress();
			}
		} else if (catchProgress > FISH_DRAIN) {
			catchProgress -= FISH_DRAIN;
		}

		if (fishMoveDir == 2) {
			fishPos = Math.min(fishPos + fishSpeed, 260);
		} else if (fishMoveDir == 0) {
			fishPos = Math.max(fishPos - fishSpeed, 20);
		}

		rodPos = Math.max(rodPos - ROD_SINK, 0); // the rod sinks unless the player lifts it
	}

	// ---------------- R E N D E R I N G ----------------

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2D = (Graphics2D) g;
		g2D.setFont(uiFont);
		g2D.setStroke(new BasicStroke(3));

		// Main display area and the three round buttons on the right
		g2D.setColor(screenBackColor);
		g2D.fillRect(screenX, 10, 540, 320);
		g2D.setColor(buttonYellow);
		g2D.fillRect(screenX - 10 + 560, 50, 90, 90);
		g2D.setColor(buttonGreen);
		g2D.fillRect(screenX - 10 + 560, 210, 90, 90);
		g2D.setColor(buttonRed);
		g2D.fillRect(screenX - 10 + 660, 130, 90, 90);
		g2D.setColor(buttonRed);
		g2D.fillRect(screenX - 10 + 730, 10, 20, 20); // close button

		// D-pad on the left
		g2D.setColor(darkGray);
		g2D.fillRect(10, 145, 50, 50); // left
		g2D.fillRect(70, 85, 50, 50); // up
		g2D.fillRect(130, 145, 50, 50); // right
		g2D.fillRect(70, 205, 50, 50); // down

		// Always-on-top toggle
		g2D.setColor(darkGray);
		g2D.fillRect(screenX - 10 + 670, 10, 40, 20);
		if (alwaysOnTop) {
			g2D.setColor(Color.green);
			g2D.fillRect(screenX - 10 + 690, 10, 20, 20);
		} else {
			g2D.setColor(screenBackColor);
			g2D.fillRect(screenX - 10 + 670, 10, 20, 20);
		}

		// Outlines
		g2D.setColor(darkGray);
		g2D.drawRect(1, 1, 948, 338);
		g2D.drawRect(screenX, 10, 540, 320);
		g2D.drawRect(screenX - 10 + 560, 50, 90, 90);
		g2D.drawRect(screenX - 10 + 560, 210, 90, 90);
		g2D.drawRect(screenX - 10 + 660, 130, 90, 90);
		g2D.drawRect(screenX - 10 + 670, 10, 40, 20);
		g2D.drawRect(screenX - 10 + 730, 10, 20, 20);

		g2D.setColor(screenFrontColor);
		if (plants.isEmpty() || bodyParts.isEmpty() || icons.isEmpty()) {
			// Assets live in the working directory - running from elsewhere finds nothing
			g2D.drawString("Assets missing - run the game from the project folder.", screenX + 40, 170);
			return;
		}

		switch (state) {
		case 0:
			paintPot(g2D);
			break;
		case 1:
			paintShop(g2D);
			break;
		case 2:
			paintSaleResult(g2D);
			break;
		case 4:
			paintCustomization(g2D);
			break;
		case 5:
			paintUpgrades(g2D);
			break;
		case 6:
			paintCustomer(g2D, "Id like one " + plants.get(customerWantsPlant).name + " please", true);
			break;
		case 7:
			paintCustomer(g2D, "You got it!", false);
			break;
		case 8:
			paintCustomer(g2D, "Thank you! <3 ", true);
			break;
		case 9:
			paintRoom(g2D);
			break;
		case 10:
			paintFishing(g2D);
			break;
		case 11:
			paintFishingSuccess(g2D);
			break;
		case 12:
			paintTutorial(g2D);
			break;
		default:
			break; // nothing to draw
		}

		// Re-outline on top of whatever the screen drew
		g2D.setColor(darkGray);
		g2D.drawRect(1, 1, 948, 338);
		g2D.drawRect(screenX - 10 + 10, 10, 540, 320);
		g2D.drawRect(screenX - 10 + 560, 50, 90, 90);
		g2D.drawRect(screenX - 10 + 560, 210, 90, 90);
		g2D.drawRect(screenX - 10 + 660, 130, 90, 90);
		g2D.drawRect(screenX - 10 + 670, 10, 40, 20);
		g2D.drawRect(screenX - 10 + 730, 10, 20, 20);
	}

	/** Screen 0: the pot being tended, with its hydration bar. */
	private void paintPot(Graphics2D g2D) {
		Plant growing = plants.get(potPlant[currentPot]);
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 4);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, 2);
		// Third button is "harvest" when ready or the pot is dead, "water" otherwise
		boolean actionButton = isReadyToHarvest(currentPot) || potWithered[currentPot];
		renderIcon(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, actionButton ? 5 : 1);

		g2D.setColor(screenFrontColor);
		g2D.drawString(growing.name, screenX - 10 + 325, 50);
		g2D.drawString(potStatus(currentPot), screenX - 10 + 325, 70);
		g2D.drawString("$ : " + money, screenX + 425, 30);

		// Hydration bar
		for (int i = 0; i < hydration[currentPot]; i++) {
			g2D.fillRect(screenX - 10 + 500, 50 * i + 25, 40, 40);
		}
		g2D.drawRect(screenX - 10 + 495, 20, 50, 300);

		// The plant itself, greyed out once it has withered
		Color plantColor = potWithered[currentPot] ? lightGray : screenFrontColor;
		for (int i = 0; i < 30; i++) {
			for (int j = 0; j < 30; j++) {
				g2D.setColor(growing.pixels[i + (j * 30)][growthFrame[currentPot]] == 0 ? plantColor : screenBackColor);
				g2D.fillRect(screenX - 10 + (i * 10) + 21, (j * 10) + 21, 8, 8);
			}
		}

		g2D.setColor(screenFrontColor);
		g2D.drawRect(screenX - 10 + 20, 20, 300, 300);
		renderPlayer(g2D, screenX - 10 + 330, 170, 5, screenFrontColor);
	}

	/** One line describing what a pot is doing, shared by screen 0 and the room. */
	private String potStatus(int pot) {
		if (potWithered[pot]) {
			return "withered - plant a new seed";
		}
		if (growthSecondsLeft[pot] <= 0) {
			return "Harvest now!";
		}
		if (hydration[pot] <= 0) {
			// Warn before the plant is actually lost
			int left = (WITHER_SECONDS - secondsDry[pot]) / 60;
			return "too dry to grow - withers in " + (left + 1) + "m";
		}
		return growthSecondsLeft[pot] / 60 + "m, " + growthSecondsLeft[pot] % 60 + "s left.";
	}

	/** Screen 1: the 3x3 seed shop. */
	private void paintShop(Graphics2D g2D) {
		Plant selected = plants.get(shopSelection);
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 6);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, 7);
		renderIcon(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, 5);

		g2D.setColor(screenFrontColor);
		g2D.drawString("$ : " + money, screenX - 10 + 445, 30);
		g2D.drawString(selected.name, screenX - 10 + 325, 50);
		g2D.drawString(selected.frameCount - 1 + " hours long", screenX - 10 + 325, 70);
		g2D.drawString("Today's price: " + selected.price + " $" + (selected.price > money ? " - Can't buy" : ""),
				screenX - 10 + 325, 90);
		g2D.drawString("Todays's Sell: " + selected.sellPrice + " $", screenX - 10 + 325, 110);
		g2D.drawString("Rich with:", screenX - 10 + 325, 200);
		g2D.drawString(selected.nutrient1, screenX - 10 + 325, 230);
		g2D.drawString(selected.nutrient2, screenX - 10 + 325, 250);
		g2D.drawString(selected.nutrient3, screenX - 10 + 325, 270);

		// All nine crops, laid out by column (p / 3) and row (p % 3)
		for (int p = 0; p < 9; p++) {
			Plant crop = plants.get(p);
			for (int i = 0; i < 30; i++) {
				for (int j = 0; j < 30; j++) {
					if (crop.pixels[i + (j * 30)][crop.frameCount - 1] == 0) {
						g2D.setColor(crop.price > money ? lightGray : screenFrontColor); // greyed out if unaffordable
					} else {
						g2D.setColor(screenBackColor);
					}
					g2D.fillRect(screenX - 10 + (i * 3) + 100 * (p / 3) + 25, (j * 3) + 100 * (p % 3) + 25, 3, 3);
				}
			}
			if (p == shopSelection) {
				g2D.setColor(screenFrontColor);
				g2D.drawRect(screenX - 10 + 100 * (p / 3) + 25, 100 * (p % 3) + 25, 90, 90);
			}
			if (p == customerWantsPlant) { // tick on whatever the customer ordered
				renderIcon(g2D, screenX - 10 + 100 * (p / 3) + 28, 100 * (p % 3) + 25, 1, buttonGreenDark, 5);
			}
		}

		g2D.setColor(screenFrontColor);
		g2D.drawRect(screenX - 10 + 20, 20, 300, 300);
	}

	/** Screen 2: shown right after a harvest. */
	private void paintSaleResult(Graphics2D g2D) {
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 5);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, 5);
		renderIcon(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, 5);
		g2D.setColor(screenFrontColor);
		g2D.drawString("$ : " + money, screenX - 10 + 445, 30);
		g2D.drawString("Earned: " + lastSaleAmount + "$", screenX - 10 + 200, 150);
		g2D.drawString("Press Any button to continue...", screenX - 10 + 200, 190);
	}

	/** Screen 4: pick hair / shirt / eyes / mouth. */
	private void paintCustomization(Graphics2D g2D) {
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 6);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, 9);
		renderIcon(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, 5);

		renderPlayer(g2D, screenX - 10 + 20, 20, 10, screenFrontColor);
		g2D.setColor(screenFrontColor);
		g2D.drawString("Hair: " + playerHair, screenX - 10 + 325, 90);
		g2D.drawString("Shirt: " + playerShirt, screenX - 10 + 325, 110);
		g2D.drawString("Eyes: " + playerEyes, screenX - 10 + 325, 130);
		g2D.drawString("Mouth: " + playerMouth, screenX - 10 + 325, 150);
		g2D.drawString("< < <", screenX - 10 + 400, 20 * bodyPartSelection + 90); // marks the row being edited
	}

	/** Screen 5: the six upgrades. */
	private void paintUpgrades(Graphics2D g2D) {
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 4);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, 7);
		renderIcon(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, 5);

		g2D.setColor(screenFrontColor);
		g2D.drawString("$ : " + money, screenX - 10 + 445, 30);
		g2D.drawString("dehydrate time       : -" + upgradeDehydrateTime + "s", screenX - 10 + 325, 70);
		g2D.drawString("dehydrate chance     : " + upgradeDehydrateChance + "%", screenX - 10 + 325, 90);
		g2D.drawString("double money         : " + upgradeDoubleMoney + "%", screenX - 10 + 325, 110);
		g2D.drawString("no money use         : " + upgradeFreeSeedChance + "%", screenX - 10 + 325, 130);
		g2D.drawString("plant growth speed   : -" + upgradeGrowthSpeed + "s", screenX - 10 + 325, 150);
		g2D.drawString("instant growth chance: " + upgradeInstantGrowChance + "%", screenX - 10 + 325, 170);
		g2D.drawString(" >>", screenX - 10 + 305, 20 * upgradeSelection + 70);

		g2D.drawString("Description:", screenX - 10 + 30, 220);
		String description;
		switch (upgradeSelection) {
		case 0:
			description = " Increase the time the plants takes to lose water.";
			break;
		case 1:
			description = " increase the chance of the plants not losing water..";
			break;
		case 2:
			description = " chance for harvested plant to earn double the price when sold.";
			break;
		case 3:
			description = " chance to buy crop for free.";
			break;
		case 4:
			description = " decrease the time it takes for the plants to grow.";
			break;
		default:
			description = " chance for the plants to instantly grow when planted.";
			break;
		}
		g2D.drawString(description, screenX - 10 + 30, 250);

		if (money >= upgradeCost()) {
			g2D.drawString("Pay " + upgradeCost() + "$ to upgrade ", screenX - 10 + 50, 110);
		} else {
			g2D.drawString("Can't Afford (" + upgradeCost() + "$)", screenX - 10 + 50, 110);
		}
	}

	/** Screens 6-8: the customer conversation. The speaker is drawn in the darker color. */
	private void paintCustomer(Graphics2D g2D, String line, boolean customerSpeaking) {
		renderCustomer(g2D, screenX - 10 + 20, 20, 5, customerSpeaking ? screenFrontColor : lightGray);
		renderPlayer(g2D, screenX - 10 + 350, 20, 5, customerSpeaking ? lightGray : screenFrontColor);
		g2D.setColor(darkGray);
		g2D.drawString(line, screenX - 10 + 150, 250);
	}

	/** Screen 9: the walk-around room. */
	private void paintRoom(Graphics2D g2D) {
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 5);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, 5);
		renderIcon(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, 5);

		drawRoomGrid(g2D, true);

		if (nearbyTile != 0) { // speech box describing whatever is next to the player
			g2D.setColor(lightGray);
			g2D.fillRect(screenX + 170, 250, 200, 50);
			g2D.setColor(screenFrontColor);
			g2D.drawRect(screenX + 170, 250, 200, 50);
			g2D.drawString(nearbyTileLabel(), screenX + 180, 280);
		}

		g2D.setColor(screenFrontColor);
		g2D.drawString("$ : " + money, screenX + 480, 30);
	}

	/** Text for the room's speech box, based on what the player is standing next to. */
	private String nearbyTileLabel() {
		if (nearbyTile == 5) {
			return "wants " + plants.get(customerWantsPlant).name + ".";
		}
		if (nearbyTile >= 9 && nearbyTile <= 11) { // the three pots
			return potStatus(nearbyTile - 9);
		}
		return tileNames[nearbyTile] + ".";
	}

	/** Draws the 9x5 room grid. The player is hidden during the early tutorial steps. */
	private void drawRoomGrid(Graphics2D g2D, boolean showPlayer) {
		g2D.setColor(lightGray);
		for (int col = 0; col < 9; col++) {
			for (int row = 0; row < 5; row++) {
				renderTile(g2D, screenX + (col * 60), 20 + (row * 60), 2, screenFrontColor, roomTiles[row][col]);
				if (showPlayer && col == playerCol && row == playerRow) {
					renderPlayer(g2D, screenX + (col * 60), 20 + (row * 60), 2, screenFrontColor);
					g2D.setColor(lightGray);
				}
				if (col == 3 && row == 0 && (state != 12 || tutorialStep > 3)) { // the customer
					renderCustomer(g2D, screenX + (col * 60), 20 + (row * 60), 2, screenFrontColor);
				}
				if (roomTiles[row][col] >= 9 && roomTiles[row][col] <= 11) { // pots show their crop
					int pot = roomTiles[row][col] - 9;
					renderPotPlant(g2D, screenX + (col * 60), -10 + (row * 60), 2,
							potWithered[pot] ? lightGray : darkGray, pot);
				}
			}
		}
	}

	/** Screen 10: the fishing minigame. */
	private void paintFishing(Graphics2D g2D) {
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 4);
		renderFish(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, 1);

		g2D.setColor(screenFrontColor);
		g2D.drawRect(screenX + 10, 20, 30, 300); // rod track
		g2D.fillRect(screenX + 10, 320 - rodHeight - rodPos, 30, rodHeight);

		g2D.drawRect(screenX + 500, 20, 30, 300); // catch meter
		g2D.fillRect(screenX + 500, 20, 30, catchProgress * 3);

		g2D.setColor(darkGray);
		g2D.fillRect(screenX + 10, 20 + fishPos, 30, 30);
		renderFish(g2D, screenX + 10, 20 + fishPos, 1, screenBackColor, caughtFishSprite);

		renderPlayer(g2D, screenX + 270, 240, 3, screenFrontColor);
		renderFish(g2D, screenX + 180, 240, 3, screenFrontColor, 1);
	}

	/** Screen 11: shown after landing a fish. */
	private void paintFishingSuccess(Graphics2D g2D) {
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 5);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, 5);
		renderFish(g2D, screenX + 225, 130, 3, buttonRedDark, caughtFishSprite);

		g2D.setColor(lightGray);
		g2D.fillRect(screenX + 170, 250, 200, 50);
		g2D.setColor(screenFrontColor);
		g2D.drawRect(screenX + 170, 250, 200, 50);
		g2D.drawString("caught fish! +" + FISH_REWARD + "$", screenX + 180, 280);
	}

	/** Screen 12: the room dimmed behind a tutorial caption. */
	private void paintTutorial(Graphics2D g2D) {
		// Only the second button does anything on the welcome step
		renderIcon(g2D, screenX - 10 + 560, 50, 3, buttonYellowDark, 5);
		renderIcon(g2D, screenX - 10 + 560, 210, 3, buttonGreenDark, tutorialStep == 0 ? 8 : 5);
		renderIcon(g2D, screenX - 10 + 660, 130, 3, buttonRedDark, 5);

		drawRoomGrid(g2D, false);

		g2D.setColor(fadeOverlay);
		switch (tutorialStep) {
		case 2:
			g2D.fillRect(screenX, 10, 420, 320); // leave the pots visible
			break;
		case 3:
			g2D.fillRect(screenX + 120, 10, 420, 320); // leave the left wall visible
			break;
		case 4:
		case 5:
			g2D.fillRect(screenX, 140, 540, 190); // leave the customer visible
			break;
		default:
			g2D.fillRect(screenX, 10, 540, 320);
			break;
		}

		g2D.setColor(Color.white);
		switch (tutorialStep) {
		case 0:
			g2D.drawString("Welcome To Floragachi", screenX + 190, 160);
			g2D.drawString("( ? ) for tutorial", screenX + 225, 220);
			break;
		case 1:
			g2D.drawString("Here is a quick guide on", screenX + 190, 160);
			g2D.drawString("how this game works!", screenX + 198, 190);
			break;
		case 2:
			g2D.drawString("on the right are your plants", screenX + 170, 160);
			g2D.drawString("Water them or they wither!", screenX + 175, 190);
			break;
		case 3:
			g2D.drawString("on the Left are Upgrade-Box and Fishing", screenX + 125, 160);
			g2D.drawString("Try interacting with them to learn more", screenX + 130, 190);
			break;
		case 4:
			g2D.drawString("Oh Look! A customer!", screenX + 190, 190);
			g2D.drawString("Grow what they want for a big tip!", screenX + 140, 220);
			break;
		default:
			g2D.drawString("Movement from the buttons on the left", screenX + 130, 190);
			g2D.drawString("Good luck <3", screenX + 220, 220);
			break;
		}
	}

	// ---------------- S P R I T E   H E L P E R S ----------------

	/** Draws the player, layering hair / shirt / eyes / mouth into one 30x30 sprite. */
	public void renderPlayer(Graphics2D g2D, int x, int y, int size, Color c) {
		renderBody(g2D, x, y, size, c, playerHair, playerShirt, playerEyes, playerMouth);
	}

	/** Same as renderPlayer, but for the randomly generated customer. */
	public void renderCustomer(Graphics2D g2D, int x, int y, int size, Color c) {
		renderBody(g2D, x, y, size, c, customerHair, customerShirt, customerEyes, customerMouth);
	}

	private void renderBody(Graphics2D g2D, int x, int y, int size, Color c, int hair, int shirt, int eyes, int mouth) {
		// The four part sets are stored back to back in one list, 5 sprites each
		features hairSprite = bodyParts.get(hair);
		features shirtSprite = bodyParts.get(shirt + 5);
		features eyesSprite = bodyParts.get(eyes + 10);
		features mouthSprite = bodyParts.get(mouth + 15);
		for (int i = 0; i < 30; i++) {
			for (int j = 0; j < 30; j++) {
				int p = i + (j * 30);
				boolean drawn = hairSprite.pixels[p] == 0 || shirtSprite.pixels[p] == 0 || eyesSprite.pixels[p] == 0
						|| mouthSprite.pixels[p] == 0;
				g2D.setColor(drawn ? c : screenBackColor);
				g2D.fillRect((i * size) + x, (j * size) + y, size, size);
			}
		}
	}

	public void renderIcon(Graphics2D g2D, int x, int y, int size, Color c, int icon) {
		renderSprite(g2D, x, y, size, c, icons, icon);
	}

	public void renderFish(Graphics2D g2D, int x, int y, int size, Color c, int icon) {
		renderSprite(g2D, x, y, size, c, fishSprites, icon);
	}

	public void renderTile(Graphics2D g2D, int x, int y, int size, Color c, int tile) {
		renderSprite(g2D, x, y, size, c, furnitureSprites, tile);
	}

	/** Draws one sprite, leaving the background untouched where it has no pixels. */
	private void renderSprite(Graphics2D g2D, int x, int y, int size, Color c, ArrayList<features> set, int index) {
		if (index < 0 || index >= set.size()) {
			return; // asset file shorter than expected
		}
		g2D.setColor(c);
		features sprite = set.get(index);
		for (int i = 0; i < 30; i++) {
			for (int j = 0; j < 30; j++) {
				if (sprite.pixels[i + (j * 30)] == 0) {
					g2D.fillRect((i * size) + x, (j * size) + y, size, size);
				}
			}
		}
	}

	/** Draws the crop growing in the given pot, at its current growth stage. */
	public void renderPotPlant(Graphics2D g2D, int x, int y, int size, Color c, int pot) {
		g2D.setColor(c);
		Plant crop = plants.get(potPlant[pot]);
		for (int i = 0; i < 30; i++) {
			for (int j = 0; j < 30; j++) {
				if (crop.pixels[i + (j * 30)][growthFrame[pot]] == 0) {
					g2D.fillRect((i * size) + x, (j * size) + y, size, size);
				}
			}
		}
	}

	// ---------------- R O O M   L A Y O U T ----------------

	/** Fills roomTiles with the fixed shop layout. */
	public void setupRoom() {
		for (int col = 0; col < 9; col++) {
			roomTiles[1][col] = 4; // counter
			roomTiles[0][col] = 6; // back wall
		}
		roomTiles[1][3] = 5; // customer stands here
		roomTiles[1][6] = 2; // closet
		roomTiles[2][8] = 9; // the three pots
		roomTiles[3][8] = 10;
		roomTiles[4][8] = 11;
		roomTiles[2][0] = 3; // upgrade booth
		roomTiles[3][0] = 12; // fishing pond
		roomTiles[4][0] = 12;
		roomTiles[0][7] = 7; // wall decoration
		roomTiles[0][1] = 8;
		roomTiles[0][6] = 8;
		System.out.print("Set Furniture.\n");
	}

	/** True for tiles interactWithNearby() can actually open - walls and decor are not. */
	private boolean isInteractive(int tile) {
		return tile == 2 || tile == 3 || tile == 5 || (tile >= 9 && tile <= 12);
	}

	/** Records what the player is standing next to, checking all four neighbours. */
	public void checkSurroundings() {
		nearbyTile = 0;
		if (playerCol < 8 && isInteractive(roomTiles[playerRow][playerCol + 1])) {
			nearbyTile = roomTiles[playerRow][playerCol + 1];
		}
		if (playerCol > 0 && isInteractive(roomTiles[playerRow][playerCol - 1])) {
			nearbyTile = roomTiles[playerRow][playerCol - 1];
		}
		if (playerRow < 4 && isInteractive(roomTiles[playerRow + 1][playerCol])) {
			nearbyTile = roomTiles[playerRow + 1][playerCol];
		}
		if (playerRow > 0 && isInteractive(roomTiles[playerRow - 1][playerCol])) {
			nearbyTile = roomTiles[playerRow - 1][playerCol];
		}
	}

	/** Opens the screen belonging to whatever the player is standing next to. */
	public void interactWithNearby() {
		switch (nearbyTile) {
		case 2:
			bodyPartSelection = 0;
			state = 4;
			break;
		case 3:
			state = 5;
			break;
		case 9:
		case 10:
		case 11:
			currentPot = nearbyTile - 9;
			state = 0;
			break;
		case 12:
			// Sprites 0 and 1 are the empty slot and the rod, so real fish start at 2
			caughtFishSprite = fishSprites.size() > 2 ? random.nextInt(fishSprites.size() - 2) + 2 : 0;
			catchProgress = 0;
			fishTurnCountdown = 0;
			state = 10;
			break;
		}
	}

	// ---------------- I N P U T ----------------

	@Override
	public void mousePressed(MouseEvent e) {
		if (e.getButton() != MouseEvent.BUTTON1) {
			return;
		}
		dragOrigin = null;
		// The buttons are drawn, not real components, so clicks are hit-tested by hand
		if (inside(e, screenX - 10 + 560, 50, 90, 90)) {
			pressButton1();
		} else if (inside(e, screenX - 10 + 560, 210, 90, 90)) {
			pressButton2();
		} else if (inside(e, screenX - 10 + 660, 130, 90, 90)) {
			pressButton3();
		} else if (inside(e, screenX - 10 + 670, 10, 40, 20)) {
			alwaysOnTop = !alwaysOnTop;
			parentFrame.setAlwaysOnTop(alwaysOnTop);
		} else if (inside(e, screenX - 10 + 730, 10, 20, 20)) {
			closeApp();
		} else if (inside(e, 10, 145, 50, 50)) {
			pressLeft();
		} else if (inside(e, 70, 85, 50, 50)) {
			pressUp();
		} else if (inside(e, 70, 205, 50, 50)) {
			pressDown();
		} else if (inside(e, 130, 145, 50, 50)) {
			pressRight();
		} else {
			dragOrigin = e.getPoint(); // empty space drags the window, which has no title bar
		}
		repaint();
	}

	@Override
	public void mouseDragged(MouseEvent e) {
		if (dragOrigin == null) {
			return;
		}
		Point at = parentFrame.getLocation();
		parentFrame.setLocation(at.x + e.getX() - dragOrigin.x, at.y + e.getY() - dragOrigin.y);
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		dragOrigin = null;
	}

	private boolean inside(MouseEvent e, int x, int y, int width, int height) {
		return e.getX() > x && e.getX() < x + width && e.getY() > y && e.getY() < y + height;
	}

	@Override
	public void keyTyped(KeyEvent e) {
		switch (e.getKeyChar()) {
		case 'i':
			pressButton1();
			break;
		case 'm':
			pressButton2();
			break;
		case 'k':
		case 'l':
			pressButton3();
			break;
		case 'a':
			pressLeft();
			break;
		case 'd':
			pressRight();
			break;
		case 'w':
			pressUp();
			break;
		case 's':
			pressDown();
			break;
		case 'v':
			// Toggle the tutorial overlay
			if (state == 12) {
				advanceTutorial();
			} else {
				state = 12;
				tutorialStep = 0;
			}
			break;

		// ---- DEBUG KEYS - remove before release ----
		case 'p': // drain one water unit
			if (hydration[currentPot] > 0) {
				hydration[currentPot]--;
			}
			break;
		case '[': // rewind growth by a minute
			growthSecondsLeft[currentPot] = Math.max(growthSecondsLeft[currentPot] - 60, 0);
			growthFrame[currentPot] = frameForTimeLeft(currentPot);
			break;
		case ']': // skip growth forward by a minute
			growthSecondsLeft[currentPot] += 60;
			growthFrame[currentPot] = frameForTimeLeft(currentPot);
			break;
		// ---- end debug keys ----

		default:
			break;
		}
		repaint();
	}

	/** Moves to the next tutorial step, dropping into the room after the last one. */
	private void advanceTutorial() {
		tutorialStep++;
		if (tutorialStep >= 6) {
			enterRoom();
		}
	}

	private void enterRoom() {
		state = 9;
		checkSurroundings();
	}

	public void pressUp() {
		switch (state) {
		case 1:
			shopSelection = (shopSelection + 8) % 9; // one row up, wrapping
			break;
		case 4:
			bodyPartSelection = (bodyPartSelection + 3) % 4;
			break;
		case 5:
			upgradeSelection = (upgradeSelection + 5) % 6;
			break;
		case 9:
			if (playerRow > 0 && roomTiles[playerRow - 1][playerCol] == 0) {
				playerRow--;
			}
			break;
		}
		checkSurroundings();
	}

	public void pressDown() {
		switch (state) {
		case 1:
			shopSelection = (shopSelection + 1) % 9;
			break;
		case 4:
			bodyPartSelection = (bodyPartSelection + 1) % 4;
			break;
		case 5:
			upgradeSelection = (upgradeSelection + 1) % 6;
			break;
		case 9:
			if (playerRow < 4 && roomTiles[playerRow + 1][playerCol] == 0) {
				playerRow++;
			}
			break;
		}
		checkSurroundings();
	}

	public void pressRight() {
		switch (state) {
		case 0:
			currentPot = (currentPot + 1) % POT_COUNT;
			break;
		case 1:
			shopSelection = (shopSelection + 3) % 9; // one column right
			break;
		case 4:
			cycleBodyPart(1);
			break;
		case 9:
			if (playerCol < 8 && roomTiles[playerRow][playerCol + 1] == 0) {
				playerCol++;
			}
			break;
		}
		checkSurroundings();
	}

	public void pressLeft() {
		switch (state) {
		case 0:
			currentPot = (currentPot + POT_COUNT - 1) % POT_COUNT;
			break;
		case 1:
			shopSelection = (shopSelection + 6) % 9; // one column left
			break;
		case 4:
			cycleBodyPart(-1);
			break;
		case 9:
			if (playerCol > 0 && roomTiles[playerRow][playerCol - 1] == 0) {
				playerCol--;
			}
			break;
		}
		checkSurroundings();
	}

	/** Steps the selected body part forward or back through its 5 options. */
	private void cycleBodyPart(int step) {
		switch (bodyPartSelection) {
		case 0:
			playerHair = (playerHair + step + 5) % 5;
			break;
		case 1:
			playerShirt = (playerShirt + step + 5) % 5;
			break;
		case 2:
			playerEyes = (playerEyes + step + 5) % 5;
			break;
		case 3:
			playerMouth = (playerMouth + step + 5) % 5;
			break;
		}
	}

	/** Top-right button. Mostly "back" or "previous". */
	public void pressButton1() {
		switch (state) {
		case 0:
			enterRoom();
			break;
		case 1:
			shopSelection = (shopSelection + 8) % 9;
			break;
		case 2:
			leaveSaleResult();
			break;
		case 4:
			cycleBodyPart(1);
			break;
		case 5:
			enterRoom();
			break;
		case 6:
			state = 7;
			break;
		case 7:
			state = 8;
			break;
		case 8:
			state = 1;
			break;
		case 9:
			interactWithNearby();
			break;
		case 10:
		case 11:
			enterRoom();
			break;
		case 12:
			if (tutorialStep == 0) {
				enterRoom(); // skip the tutorial
			} else {
				advanceTutorial();
			}
			break;
		}
	}

	/** Bottom-right button. Mostly "next" or "shuffle". */
	public void pressButton2() {
		switch (state) {
		case 0:
			state = 5;
			break;
		case 1:
			shopSelection = (shopSelection + 1) % 9;
			break;
		case 2:
			leaveSaleResult();
			break;
		case 4:
			// Randomize the whole look
			playerHair = random.nextInt(5);
			playerShirt = random.nextInt(5);
			playerEyes = random.nextInt(5);
			playerMouth = random.nextInt(5);
			break;
		case 5:
			upgradeSelection = (upgradeSelection + 1) % 6;
			break;
		case 6:
			state = 7;
			break;
		case 7:
			state = 8;
			break;
		case 8:
			state = 1;
			break;
		case 9:
			interactWithNearby();
			break;
		case 11:
			enterRoom();
			break;
		case 12:
			advanceTutorial();
			break;
		}
	}

	/** The big middle button: the "do it" action on every screen. */
	public void pressButton3() {
		switch (state) {
		case 0:
			if (potWithered[currentPot]) {
				state = 1; // dead pot: the only useful action is buying a new seed
			} else if (isReadyToHarvest(currentPot)) {
				harvest();
			} else {
				hydration[currentPot] = MAX_HYDRATION; // otherwise it waters the pot
				secondsDry[currentPot] = 0;
			}
			break;
		case 1:
			buySeed();
			break;
		case 2:
			leaveSaleResult();
			break;
		case 4:
			saveProgress();
			enterRoom();
			break;
		case 5:
			buyUpgrade();
			break;
		case 6:
			state = 7;
			break;
		case 7:
			state = 8;
			break;
		case 8:
			state = 1;
			break;
		case 9:
			interactWithNearby();
			break;
		case 10:
			rodPos = Math.min(rodPos + ROD_LIFT, 300 - rodHeight); // lift the rod
			break;
		case 12:
			if (tutorialStep == 0) {
				enterRoom();
			} else {
				advanceTutorial();
			}
			break;
		}
	}

	/** After the "you earned $N" screen, go greet the customer if their order was filled. */
	private void leaveSaleResult() {
		if (customerOrderFilled) {
			customerOrderFilled = false;
			state = 6;
		} else {
			state = 1;
		}
	}

	// ---------------- G A M E   A C T I O N S ----------------

	/** Sells the crop in the current pot. */
	private void harvest() {
		Plant harvested = plants.get(potPlant[currentPot]);
		lastSaleAmount = harvested.sellPrice;
		if (random.nextInt(100) < upgradeDoubleMoney) {
			lastSaleAmount *= 2;
		}
		money += lastSaleAmount;
		if (potPlant[currentPot] == customerWantsPlant) { // it was what the customer ordered
			customerOrderFilled = true;
			money += CUSTOMER_BONUS;
			randomizeCustomer();
		}
		state = 2;
		saveProgress();
	}

	/** Buys the highlighted seed and plants it in the current pot. */
	private void buySeed() {
		Plant chosen = plants.get(shopSelection);
		if (chosen.price > money) {
			return;
		}
		if (random.nextInt(100) >= upgradeFreeSeedChance) { // upgrade can make it free
			money -= chosen.price;
		}
		potPlant[currentPot] = shopSelection;
		potWithered[currentPot] = false; // a new seed revives a dead pot
		if (random.nextInt(100) < upgradeInstantGrowChance) {
			growthSecondsLeft[currentPot] = 0; // instant-grow upgrade
		} else {
			growthSecondsLeft[currentPot] = (chosen.frameCount - 1) * secondsPerStage();
		}
		growthFrame[currentPot] = frameForTimeLeft(currentPot);
		hydration[currentPot] = MAX_HYDRATION;
		secondsDry[currentPot] = 0;
		secondsUntilDehydrate[currentPot] = dehydrateInterval + upgradeDehydrateTime;
		state = 0;
		saveProgress();
	}

	/** Upgrades get more expensive as you buy them, so there is a curve to climb. */
	private int upgradeCost() {
		return UPGRADE_BASE_COST * (upgradesBought + 1);
	}

	/** Buys the highlighted upgrade. Values are capped so the game stays playable. */
	public void buyUpgrade() {
		if (money < upgradeCost()) {
			return;
		}
		money -= upgradeCost();
		upgradesBought++;
		switch (upgradeSelection) {
		case 0:
			upgradeDehydrateTime += 5;
			break;
		case 1:
			upgradeDehydrateChance = Math.min(upgradeDehydrateChance + 10, 90); // never fully immune
			break;
		case 2:
			upgradeDoubleMoney = Math.min(upgradeDoubleMoney + 10, 100);
			break;
		case 3:
			upgradeFreeSeedChance = Math.min(upgradeFreeSeedChance + 10, 100);
			break;
		case 4:
			// Capped so secondsPerStage() never reaches zero
			upgradeGrowthSpeed = Math.min(upgradeGrowthSpeed + 5, SECONDS_PER_STAGE - 5);
			break;
		case 5:
			upgradeInstantGrowChance = Math.min(upgradeInstantGrowChance + 10, 100);
			break;
		}
		saveProgress();
	}

	/** Generates the next customer and the crop they want. */
	public void randomizeCustomer() {
		customerWantsPlant = random.nextInt(9);
		customerHair = random.nextInt(5);
		customerShirt = random.nextInt(5);
		customerEyes = random.nextInt(5);
		customerMouth = random.nextInt(5);
		customerPatience = CUSTOMER_PATIENCE;
	}

	public void closeApp() {
		saveProgress();
		System.out.print("Farewell Ashen one\n");
		System.exit(0);
	}

	// ---------------- A S S E T   L O A D I N G ----------------
	// All .rona files are whitespace separated ints read from the working directory.

	/** Loads the 9 crops: name, three nutrients, frame count, then 900 ints per frame. */
	public void loadPlants() {
		// Prices are seeded from the day number, so "today's price" is the same all day
		// however often the game is restarted, and changes tomorrow.
		Random dayRandom = new Random(System.currentTimeMillis() / 86_400_000L);

		System.out.print("plants:[");
		for (int index = 0; index < 9; index++) {
			File file = new File("p" + index + ".rona");
			try (Scanner reader = new Scanner(file)) {
				String name = reader.next();
				String nutrient1 = reader.next();
				String nutrient2 = reader.next();
				String nutrient3 = reader.next();
				int frames = Integer.valueOf(reader.next());

				Plant plant = new Plant(frames);
				plant.name = name;
				plant.nutrient1 = nutrient1;
				plant.nutrient2 = nutrient2;
				plant.nutrient3 = nutrient3;
				// Both cost and profit scale with length, so slow crops are an investment
				// rather than a strictly worse choice than fast ones.
				plant.price = frames * (2 + dayRandom.nextInt(4));
				plant.sellPrice = plant.price + frames * (1 + dayRandom.nextInt(3));
				plants.add(plant);

				for (int frame = 0; frame < frames; frame++) {
					for (int p = 0; p < 30 * 30; p++) {
						plant.pixels[p][frame] = Integer.valueOf(reader.next());
					}
				}
				System.out.print(plant.name + ",");
			} catch (FileNotFoundException e) {
				System.out.println("Could not open " + file.getName());
			}
		}
		System.out.print("]\n");
	}

	public void loadBodyParts() {
		loadSprites("cosms.rona", bodyParts, "Body Features");
	}

	public void loadIcons() {
		loadSprites("Icons.rona", icons, "Icon");
	}

	public void loadFurniture() {
		loadSprites("Furniture.rona", furnitureSprites, "Furniture");
	}

	public void loadFish() {
		loadSprites("Fishin.rona", fishSprites, "Fish");
	}

	/** Reads a single-frame sprite sheet: a name, a count, then 900 ints per sprite. */
	private void loadSprites(String fileName, ArrayList<features> target, String label) {
		try (Scanner reader = new Scanner(new File(fileName))) {
			reader.next(); // the sheet's name, unused
			int count = Integer.valueOf(reader.next());
			System.out.print(label + " Count : " + count + ". \n");
			for (int s = 0; s < count; s++) {
				features sprite = new features();
				for (int p = 0; p < 30 * 30; p++) {
					sprite.pixels[p] = Integer.valueOf(reader.next());
				}
				target.add(sprite);
			}
		} catch (FileNotFoundException e) {
			System.out.println("Could not open " + fileName);
		}
	}

	// ---------------- S A V I N G ----------------

	/**
	 * Writes the save as plain whitespace separated numbers, same as it always was.
	 * Values 25 onwards were added later; older saves simply stop at 25 and the rest
	 * fall back to defaults.
	 */
	public void saveProgress() {
		secondsSinceSave = 0;
		try (FileWriter writer = new FileWriter(SAVE_FILE)) {
			StringBuilder out = new StringBuilder();
			out.append(playerHair).append(" ").append(playerShirt).append(" ");
			out.append(playerEyes).append(" ").append(playerMouth).append(" ");
			out.append(customerHair).append(" ").append(customerShirt).append(" ");
			out.append(customerEyes).append(" ").append(customerMouth).append(" ");
			out.append(customerWantsPlant).append(" ");
			out.append(hydration[0]).append(" ").append(hydration[1]).append(" ").append(hydration[2]).append(" ");
			out.append(potPlant[0]).append(" ").append(potPlant[1]).append(" ").append(potPlant[2]).append(" ");
			out.append(growthSecondsLeft[0]).append(" ").append(growthSecondsLeft[1]).append(" ")
					.append(growthSecondsLeft[2]).append(" ");
			out.append(money).append(" ");
			out.append(upgradeDehydrateTime).append(" ").append(upgradeDehydrateChance).append(" ");
			out.append(upgradeDoubleMoney).append(" ").append(upgradeFreeSeedChance).append(" ");
			out.append(upgradeGrowthSpeed).append(" ").append(upgradeInstantGrowChance).append(" ");
			// --- added later ---
			out.append(System.currentTimeMillis() / 1000L).append(" "); // for offline catch-up
			out.append(upgradesBought).append(" ");
			out.append(potWithered[0] ? 1 : 0).append(" ").append(potWithered[1] ? 1 : 0).append(" ")
					.append(potWithered[2] ? 1 : 0).append(" ");
			writer.write(out.toString());
		} catch (IOException e) {
			System.out.println("Could not write " + SAVE_FILE);
			e.printStackTrace();
		}
	}

	/** Reads the save file. A missing, short or corrupt file just leaves the defaults. */
	public void loadProgress() {
		try (Scanner reader = new Scanner(new File(SAVE_FILE))) {
			long[] v = new long[SAVE_VALUES];
			int count = 0;
			while (count < v.length && reader.hasNextLong()) {
				v[count++] = reader.nextLong();
			}
			if (count < SAVE_REQUIRED) {
				System.out.print("Save file incomplete, starting fresh.\n");
				return;
			}
			// Every index is clamped, so an edited save can't crash the renderer
			playerHair = clamp(v[0], 4);
			playerShirt = clamp(v[1], 4);
			playerEyes = clamp(v[2], 4);
			playerMouth = clamp(v[3], 4);
			customerHair = clamp(v[4], 4);
			customerShirt = clamp(v[5], 4);
			customerEyes = clamp(v[6], 4);
			customerMouth = clamp(v[7], 4);
			customerWantsPlant = clamp(v[8], 8);
			for (int pot = 0; pot < POT_COUNT; pot++) {
				hydration[pot] = clamp(v[9 + pot], MAX_HYDRATION);
				potPlant[pot] = clamp(v[12 + pot], 8);
				growthSecondsLeft[pot] = clamp(v[15 + pot], Integer.MAX_VALUE);
			}
			money = clamp(v[18], Integer.MAX_VALUE);
			upgradeDehydrateTime = clamp(v[19], Integer.MAX_VALUE);
			upgradeDehydrateChance = clamp(v[20], 90);
			upgradeDoubleMoney = clamp(v[21], 100);
			upgradeFreeSeedChance = clamp(v[22], 100);
			upgradeGrowthSpeed = clamp(v[23], SECONDS_PER_STAGE - 5);
			upgradeInstantGrowChance = clamp(v[24], 100);

			long savedAt = count > 25 ? v[25] : 0;
			upgradesBought = count > 26 ? clamp(v[26], Integer.MAX_VALUE) : 0;
			for (int pot = 0; pot < POT_COUNT && count > 27 + pot; pot++) {
				potWithered[pot] = v[27 + pot] != 0;
			}

			if (!plants.isEmpty()) {
				for (int pot = 0; pot < POT_COUNT; pot++) {
					growthFrame[pot] = frameForTimeLeft(pot);
				}
			}
			catchUpOffline(savedAt);
			System.out.print("Progress Loaded.\n");
		} catch (FileNotFoundException e) {
			System.out.print("No save file, starting fresh.\n");
		}
	}

	/** Replays the time the game spent closed, so plants keep growing while it is shut. */
	private void catchUpOffline(long savedAtEpochSeconds) {
		if (savedAtEpochSeconds <= 0) {
			return;
		}
		long elapsed = (System.currentTimeMillis() / 1000L) - savedAtEpochSeconds;
		int seconds = (int) Math.max(0, Math.min(elapsed, MAX_OFFLINE_SECONDS));
		for (int s = 0; s < seconds; s++) {
			tickPlants(false); // no withering while away
		}
		if (seconds > 0) {
			System.out.print("Away for " + seconds / 60 + "m, plants caught up.\n");
		}
	}

	private int clamp(long value, int max) {
		return (int) Math.max(0, Math.min(value, max));
	}

	// ---------------- U N U S E D   L I S T E N E R   M E T H O D S ----------------

	@Override
	public void mouseEntered(MouseEvent e) {
	}

	@Override
	public void mouseExited(MouseEvent e) {
	}

	@Override
	public void keyPressed(KeyEvent e) {
	}

	@Override
	public void keyReleased(KeyEvent e) {
	}

	@Override
	public void mouseMoved(MouseEvent e) {
	}

	@Override
	public void mouseClicked(MouseEvent e) {
	}
}
