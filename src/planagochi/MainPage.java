package planagochi;

import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.ImageIcon;
import javax.swing.JFrame;

/** The application window. Holds a single GamePage, which is the whole game. */
public class MainPage extends JFrame {

	private static final long serialVersionUID = 1L;

	// Loaded from the working directory, same as the .rona assets
	private ImageIcon logo = new ImageIcon("floralogo.png");
	private GamePage game;

	/** Builds and shows the window. */
	public void showWindow() {
		game = new GamePage(this);

		this.setTitle("Floragachi");
		this.setIconImage(logo.getImage());
		// Handled below instead of EXIT_ON_CLOSE, so alt-F4 still saves
		this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		this.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				game.closeApp();
			}
		});

		this.add(game);
		this.pack();
		this.setLocationRelativeTo(null);
		this.setMinimumSize(new Dimension(760, 340));
		this.setVisible(true);
		this.setResizable(false);
		this.setAlwaysOnTop(true);
	}
}
