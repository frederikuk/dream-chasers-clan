/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.FontManager;

/** The panel's building blocks, in the brand. */
final class Widgets
{
	static final int TEXT_WIDTH = 150;

	private Widgets()
	{
	}

	static JPanel section()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(Brand.NIGHT);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, Brand.GOLD_DEEP),
			new EmptyBorder(6, 8, 8, 6)));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	static JLabel title(String s)
	{
		JLabel l = new JLabel(s.toUpperCase());
		l.setForeground(Brand.GOLD);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setBorder(new EmptyBorder(0, 0, 4, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	static JLabel text(String htmlBody, Color colour)
	{
		JLabel l = new JLabel(html(htmlBody));
		l.setForeground(colour);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setBorder(new EmptyBorder(1, 0, 3, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	static JLabel small(String plain)
	{
		JLabel l = new JLabel(plain);
		l.setForeground(Brand.MUTED);
		l.setFont(FontManager.getRunescapeSmallFont());
		return l;
	}

	static JPanel row()
	{
		// hgap also indents the first button; the section border allows for it
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	static JButton button(String label, Runnable action)
	{
		JButton b = new JButton(label);
		b.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN));
		b.setForeground(Brand.TEXT);
		b.setBackground(Brand.ROYAL);
		b.setFocusPainted(false);
		b.setBorder(new EmptyBorder(4, 8, 4, 8));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addActionListener(e -> action.run());
		return b;
	}

	/** The one gold button in a section: the thing to press next. */
	static JButton primary(String label, Runnable action)
	{
		JButton b = button(label, action);
		b.setForeground(Brand.VOID);
		b.setBackground(Brand.GOLD);
		return b;
	}

	static void done(JPanel p)
	{
		p.revalidate();
		p.repaint();
	}

	static boolean notBlank(String s)
	{
		return s != null && !s.trim().isEmpty();
	}

	static String html(String body)
	{
		return "<html><body style='width:" + TEXT_WIDTH + "px'>" + body + "</body></html>";
	}

	static String muted(String htmlBody)
	{
		return "<font color='" + Brand.hex(Brand.MUTED) + "'>" + htmlBody + "</font>";
	}

	/** Escapes text from the site (event text, RSNs) before it goes into Swing HTML. */
	static String esc(String s)
	{
		if (s == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (char ch : s.toCharArray())
		{
			switch (ch)
			{
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				case '&':
					sb.append("&amp;");
					break;
				case '"':
					sb.append("&quot;");
					break;
				case '\'':
					sb.append("&#39;");
					break;
				default:
					sb.append(ch);
			}
		}
		return sb.toString();
	}

	static void onEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}
}
