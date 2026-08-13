package planagochi;

/** One crop type: its name, nutrients, prices and one 30x30 sprite per growth stage. */
public class Plant {
	/** pixels[pixelIndex][frame] - 0 means "draw", anything else is background. */
	public int pixels[][];
	public String name;
	/** Number of growth stages; also doubles as the plant's "hours long" figure. */
	public int frameCount;
	public int price;
	public int sellPrice;
	public String nutrient1 = "Vetamin-X1";
	public String nutrient2 = "Vetamin-X2";
	public String nutrient3 = "Vetamin-X3";

	Plant(int frames) {
		pixels = new int[30 * 30][frames]; // Java zero-fills new arrays
		frameCount = frames;
	}
}
