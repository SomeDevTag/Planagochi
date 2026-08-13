package planagochi;

/** A single 30x30 sprite: a body part, button icon, room tile or fish. */
public class features {
	/** 0 means "draw this pixel", anything else is background. */
	public int pixels[];

	features() {
		pixels = new int[30 * 30]; // Java zero-fills new arrays
	}
}
