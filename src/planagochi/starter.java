package planagochi;

import javax.swing.SwingUtilities;

/** Entry point. */
public class starter {
	public static void main(String args[]) {
		// Swing components must be built on the event dispatch thread
		SwingUtilities.invokeLater(() -> {
			MainPage window = new MainPage();
			window.setUndecorated(true);
			window.showWindow();
		});
	}
}
