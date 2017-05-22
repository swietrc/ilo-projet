package widgets;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.security.MessageDigestSpi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.StyleConstants;

import chat.Vocabulary;
import examples.widgets.ListExampleFrame;
import models.Message;
import models.NameSetListModel;

/**
 * Fenêtre d'affichae de la version GUI texte du client de chat.
 * @author davidroussel
 */
public class ClientFrame2 extends AbstractClientFrame
{

    private final SortAction sortContentAction;
    private final SortAction sortAuthorAction;
    private final SortAction sortDateAction;

    public enum SortType { AUTHOR, CONTENT, DATE }
    private final JTextArea output;

    private final KickSelectedAction kickSelectionAction;
    private final FilterSelectionAction filterSelectionAction;

    // VIVE LES LAMBDAS ! o/
    private Consumer<Message> msgPrinter = (Message msg) -> displayMessage(msg);

    /**
     * Liste des éléments à afficher dans la JList.
     * Les ajouts et retraits effectués dans cette ListModel seront alors
     * automatiquement transmis au JList contenant ce ListModel
     */
    private DefaultListModel<String> usersList = new DefaultListModel<String>();

    private ArrayList<Integer> selectedUsers;

    /**
     * Le modèle de sélection de la JList.
     * Conserve les indices des éléments sélectionnés de {@link #usersList} dans
     * la JList qui affiche ces éléments.
     */
    private ListSelectionModel selectionModel = null;

    /**
	 * Lecteur de flux d'entrée. Lit les données texte du {@link #inPipe} pour
	 * les afficher dans le {@link #document}
	 */
	private ObjectInputStream inOS;

	protected Vector<Message> messageStore = new Vector<>();

	/**
	 * Le label indiquant sur quel serveur on est connecté
	 */
	protected final JLabel serverLabel;

	/**
	 * La zone du texte à envoyer
	 */
	 private final JTextField sendTextField;

	/**
	 * Actions à réaliser lorsque l'on veut effacer le contenu du document
	 */
	private final ClearAction clearAction;

	/**
	 * Actions à réaliser lorsque l'on veut envoyer un message au serveur
	 */
	private final SendAction sendAction;

	/**
	 * Actions à réaliser lorsque l'on veut envoyer un message au serveur
	 */
	protected final QuitAction quitAction;

    /**
     * Action à réaliser lorsque l'on souhaite déselctionner tous les élements de la liste
     */
    private Action clearSelectionAction;

	/**
	 * Référence à la fenêtre courante (à utiliser dans les classes internes)
	 */
	protected final JFrame thisRef;
	NameSetListModel userList = new NameSetListModel();
	ArrayList<String> userStore = new ArrayList<>();
    private static String newline = System.getProperty("line.separator");

    /**
	 * Constructeur de la fenêtre
	 * @param name le nom de l'utilisateur
	 * @param host l'hôte sur lequel on est connecté
	 * @param commonRun état d'exécution des autres threads du client
	 * @param parentLogger le logger parent pour les messages
	 * @throws HeadlessException
	 */
	public ClientFrame2(String name,
	                   String host,
	                   Boolean commonRun,
	                   Logger parentLogger)
	    throws HeadlessException
	{
		super(name, host, commonRun, parentLogger);
		thisRef = this;

		// --------------------------------------------------------------------
		// Flux d'IO
		//---------------------------------------------------------------------
		/*
		 * Attention, la création du flux d'entrée doit (éventuellement) être
		 * reportée jusqu'au lancement du run dans la mesure où le inPipe
		 * peut ne pas encore être connecté à un PipedOutputStream
		 */

		// --------------------------------------------------------------------
		// Création des actions send, clear et quit
		// --------------------------------------------------------------------

		sendAction = new SendAction();
		clearAction = new ClearAction();
		quitAction = new QuitAction();
		clearSelectionAction = new ClearSelectionAction();
		kickSelectionAction = new KickSelectedAction();
		filterSelectionAction = new FilterSelectionAction();
        sortContentAction = new SortAction(SortType.CONTENT);
        sortAuthorAction = new SortAction(SortType.AUTHOR);
        sortDateAction = new SortAction(SortType.DATE);

		/*
		 * Ajout d'un listener pour fermer correctement l'application lorsque
		 * l'on ferme la fenêtre. WindowListener sur this
		 */
		addWindowListener(new FrameWindowListener());

		// --------------------------------------------------------------------
		// Widgets setup (handled by Window builder)
		// --------------------------------------------------------------------

        setPreferredSize(new Dimension(800, 600));
        getContentPane().setLayout(new BorderLayout(0, 0));

        JScrollPane textScrollPane = new JScrollPane();
        getContentPane().add(textScrollPane, BorderLayout.CENTER);

        output = new JTextArea();
        textScrollPane.setViewportView(output);

        JPanel leftPanel = new JPanel();
        leftPanel.setPreferredSize(new Dimension(200, 10));
        getContentPane().add(leftPanel, BorderLayout.WEST);
        leftPanel.setLayout(new BorderLayout(0, 0));

        JScrollPane listScrollPane = new JScrollPane();
        leftPanel.add(listScrollPane, BorderLayout.CENTER);

        JList<String> list = new JList<String>(usersList);
        listScrollPane.setViewportView(list);
        list.setName("Elements");
        list.setBorder(UIManager.getBorder("EditorPane.border"));
        list.setSelectedIndex(0);
        list.setCellRenderer(new ColorTextRenderer());

        selectionModel = list.getSelectionModel();
        selectionModel.addListSelectionListener(new ListSelectionListener()
        {
            @Override
            public void valueChanged(ListSelectionEvent e)
            {
                ListSelectionModel lsm = (ListSelectionModel) e.getSource();

                int firstIndex = e.getFirstIndex();
                int lastIndex = e.getLastIndex();
                boolean isAdjusting = e.getValueIsAdjusting();
                selectedUsers = new ArrayList<>();
				/*
				 * isAdjusting remains true while events like drag n drop are
				 * still processed and becomes false afterwards.
				 */
                if (!isAdjusting)
                {
                    if (lsm.isSelectionEmpty())
                    {
                        clearSelectionAction.setEnabled(false);
                        output.append(" <none>");
                    }
                    else
                    {
                        clearSelectionAction.setEnabled(true);
                        // Find out which indexes are selected.
                        int minIndex = lsm.getMinSelectionIndex();
                        int maxIndex = lsm.getMaxSelectionIndex();
                        for (int i = minIndex; i <= maxIndex; i++)
                        {
                            if (lsm.isSelectedIndex(i))
                            {
                                selectedUsers.add(i);
                            }
                        }
                    }
                }
                else
                {
                    // Still adjusting ...
                    output.append("Processing ..." + newline);
                }
            }
        });

		JToolBar toolBar = new JToolBar();
		toolBar.setFloatable(false);
		getContentPane().add(toolBar, BorderLayout.NORTH);


		JButton quitButton = new JButton(quitAction);
		quitButton.setHideActionText(true);
		toolBar.add(quitButton);

		toolBar.add(Box.createRigidArea(new Dimension(20, 0)));
/*
		Component toolBarSep = Box.createHorizontalGlue();
		toolBar.add(toolBarSep);
*/

        JButton clearSelectionButton = new JButton(clearSelectionAction);
        clearSelectionButton.setHideActionText(true);
        toolBar.add(clearSelectionButton);

		JButton kickSelectedButton = new JButton(kickSelectionAction);
		kickSelectedButton.setHideActionText(true);
		toolBar.add(kickSelectedButton);

		JButton filterSelectionButton = new JButton(filterSelectionAction);
		filterSelectionButton.setHideActionText(true);
		toolBar.add(filterSelectionButton);

		toolBar.add(Box.createRigidArea(new Dimension(20, 0)));

		JButton clearButton = new JButton(clearAction);
        clearButton.setHideActionText(true);
		toolBar.add(clearButton);

        serverLabel = new JLabel(host == null ? "" : host);
		toolBar.add(serverLabel);

		JPanel sendPanel = new JPanel();
		getContentPane().add(sendPanel, BorderLayout.SOUTH);
		sendPanel.setLayout(new BorderLayout(0, 0));
		sendTextField = new JTextField();
		sendTextField.setAction(sendAction);
		sendPanel.add(sendTextField);
		sendTextField.setColumns(10);

		JButton sendButton = new JButton(sendAction);
		sendPanel.add(sendButton, BorderLayout.EAST);

		JScrollPane scrollPane = new JScrollPane();
		getContentPane().add(scrollPane, BorderLayout.CENTER);

		JTextPane textPane = new JTextPane();
		textPane.setEditable(false);
		// autoscroll textPane to bottom
		DefaultCaret caret = (DefaultCaret) textPane.getCaret();
		caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

		scrollPane.setViewportView(textPane);

		JMenuBar menuBar = new JMenuBar();
		setJMenuBar(menuBar);

		// ------------------------- CONNECTIONS MENU -------------------------
		JMenu connectionsMenu = new JMenu("Connections");
		menuBar.add(connectionsMenu);

        JMenuItem quitMenuItem = new JMenuItem(quitAction);
        connectionsMenu.add(quitMenuItem);

        // ------------------------- MESSAGES MENU -----------------------------

		JMenu messagesMenu = new JMenu("Messages");
        menuBar.add(messagesMenu);

        JMenuItem clearMenuItem = new JMenuItem(clearAction);
        messagesMenu.add(clearMenuItem);

        JMenuItem filterMenuitem = new JMenuItem(filterSelectionAction);
        messagesMenu.add(filterMenuitem);

        // ------------------------- USERS MENU --------------------------------

        JMenu usersMenu = new JMenu("Users");
        menuBar.add(usersMenu);

        JMenuItem clearSelectedMenuItem = new JMenuItem(clearSelectionAction);
        usersMenu.add(clearSelectedMenuItem);

        JMenuItem kickMenuItem = new JMenuItem(kickSelectionAction);
        usersMenu.add(kickMenuItem);

        JMenu sortMenu = new JMenu("Sort");
        messagesMenu.add(sortMenu);

        JMenuItem sortContentMenuItem = new JMenuItem(sortContentAction);
        sortMenu.add(sortContentMenuItem);

        JMenuItem sortAuthorMenuItem = new JMenuItem(sortAuthorAction);
        sortMenu.add(sortAuthorMenuItem);

        JMenuItem sortDateMenuItem = new JMenuItem(sortDateAction);
        sortMenu.add(sortDateMenuItem);

        // -------------------------POPUP MENU----------------------------------

        JPopupMenu popupMenu = new JPopupMenu();
        addPopup(list, popupMenu);

        JMenuItem mntmClearSelection = new JMenuItem(clearSelectionAction);
        popupMenu.add(mntmClearSelection);

        JSeparator separator = new JSeparator();
        popupMenu.add(separator);
        popupMenu.add(kickMenuItem);

        JPopupMenu rightPopup = new JPopupMenu();
        addPopup(textPane, rightPopup);
        rightPopup.add(sortMenu);


        // --------------------------------------------------------------------
		// Documents
		// récupération du document du textPane ainsi que du documentStyle et du
		// defaultColor du document
		//---------------------------------------------------------------------
		document = textPane.getStyledDocument();
		documentStyle = textPane.addStyle("New Style", null);
		defaultColor = StyleConstants.getForeground(documentStyle);


	}

	/**
	 * Adds a popup menu to a component
	 * @param component the parent component of the popup menu
	 * @param popup the popup menu to add
	 */
	private static void addPopup(Component component, final JPopupMenu popup)
	{
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showMenu(e);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showMenu(e);
				}
			}

			private void showMenu(MouseEvent e)
			{
				popup.show(e.getComponent(), e.getX(), e.getY());
			}
		});
	}

	/**
	 * Listener lorsque le bouton #btnClear est activé. Efface le contenu du
	 * {@link #document}
	 */
	protected class ClearAction extends AbstractAction
	{
		/**
		 * Constructeur d'une ClearAction : met en place le nom, la description,
		 * le raccourci clavier et les small|Large icons de l'action
		 */
		public ClearAction()
		{
			putValue(SMALL_ICON,
			         new ImageIcon(ClientFrame2.class
			             .getResource("/icons/erase-16.png")));
			putValue(LARGE_ICON_KEY,
			         new ImageIcon(ClientFrame2.class
			             .getResource("/icons/erase-32.png")));
			putValue(ACCELERATOR_KEY,
			         KeyStroke.getKeyStroke(KeyEvent.VK_L,
			                                InputEvent.META_MASK));
			putValue(NAME, "Clear Messages");
			putValue(SHORT_DESCRIPTION, "Clear document content");
		}

		/**
		 * Opérations réalisées lorsque l'action est sollicitée
		 * @param e évènement à l'origine de l'action
		 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
		 */
		@Override
		public void actionPerformed(ActionEvent e)
		{
			/*
			 * Effacer le contenu du document
			 */
			try
			{
				document.remove(0, document.getLength());
				messageStore.clear();
			}
			catch (BadLocationException ex)
			{
				logger.warning("ClientFrame: clear doc: bad location");
				logger.warning(ex.getLocalizedMessage());
			}
		}
	}

	/**
	 * Action réalisée pour envoyer un message au serveur
	 */
	protected class SendAction extends AbstractAction
	{
		/**
		 * Constructeur d'une SendAction : met en place le nom, la description,
		 * le raccourci clavier et les small|Large icons de l'action
		 */
		public SendAction()
		{
			putValue(SMALL_ICON,
			         new ImageIcon(ClientFrame2.class
			             .getResource("/icons/sent-16.png")));
			putValue(LARGE_ICON_KEY,
			         new ImageIcon(ClientFrame2.class
			             .getResource("/icons/sent-32.png")));
			putValue(ACCELERATOR_KEY,
			         KeyStroke.getKeyStroke(KeyEvent.VK_S,
			                                InputEvent.META_MASK));
			putValue(NAME, "Send");
			putValue(SHORT_DESCRIPTION, "Send text to server");
		}

		/**
		 * Opérations réalisées lorsque l'action est sollicitée
		 * @param e évènement à l'origine de l'action
		 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
		 */
		@Override
		public void actionPerformed(ActionEvent e)
		{
			/*
			 * récupération du contenu du textfield et envoi du message au
			 * serveur (ssi le message n'est pas vide), puis effacement du
			 * contenu du textfield.
			 */
			// Obtention du contenu du sendTextField
			String content = sendTextField.getText();

			// logger.fine("Le contenu du textField etait = " + content);

			// envoi du message
			if (content != null)
			{
				if (content.length() > 0)
				{
					sendMessage(content);

					// Effacement du contenu du textfield
					sendTextField.setText("");
				}
			}
		}
	}

	/**
	 * Action réalisée pour se délogguer du serveur
	 */
	private class QuitAction extends AbstractAction
	{
		/**
		 * Constructeur d'une QuitAction : met en place le nom, la description,
		 * le raccourci clavier et les small|Large icons de l'action
		 */
		public QuitAction()
		{
			putValue(SMALL_ICON,
			         new ImageIcon(ClientFrame2.class
			             .getResource("/icons/disconnected-16.png")));
			putValue(LARGE_ICON_KEY,
			         new ImageIcon(ClientFrame2.class
			             .getResource("/icons/disconnected-32.png")));
			putValue(ACCELERATOR_KEY,
			         KeyStroke.getKeyStroke(KeyEvent.VK_Q,
			                                InputEvent.META_MASK));
			putValue(NAME, "Quit");
			putValue(SHORT_DESCRIPTION, "Disconnect from server and quit");
		}

		/**
		 * Opérations réalisées lorsque l'action "quitter" est sollicitée
		 * @param e évènement à l'origine de l'action
		 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
		 */
		@Override
		public void actionPerformed(ActionEvent e)
		{
			logger.info("QuitAction: sending bye ... ");

			serverLabel.setText("");
			thisRef.validate();

			try
			{
				Thread.sleep(1000);
			}
			catch (InterruptedException e1)
			{
				return;
			}

			sendMessage(Vocabulary.byeCmd);
			System.exit(0);
		}
	}

    private class FilterSelectionAction extends AbstractAction
    {
        /**
         * Constructeur d'une QuitAction : met en place le nom, la description,
         * le raccourci clavier et les small|Large icons de l'action
         */
        public FilterSelectionAction()
        {
            putValue(SMALL_ICON,
                    new ImageIcon(ClientFrame2.class
                            .getResource("/icons/filled_filter-16.png")));
            putValue(LARGE_ICON_KEY,
                    new ImageIcon(ClientFrame2.class
                            .getResource("/icons/filled_filter-32.png")));
            putValue(ACCELERATOR_KEY,
                    KeyStroke.getKeyStroke(KeyEvent.VK_Q,
                            InputEvent.META_MASK));
            putValue(NAME, "Quit");
            putValue(SHORT_DESCRIPTION, "Disconnect from server and quit");
        }

        /**
         * Opérations réalisées lorsque l'action "quitter" est sollicitée
         * @param e évènement à l'origine de l'action
         * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
         */
        @Override
        public void actionPerformed(ActionEvent e)
        {
            try {
                document.remove(0, document.getLength());
            } catch (BadLocationException e1) {
                e1.printStackTrace();
            }

            ArrayList<String> selUserList = new ArrayList<>();

            if (!selectedUsers.isEmpty()) {
                for (int i : selectedUsers) {
                    selUserList.add(usersList.getElementAt(i));
                }
            }

            for (Message m : messageStore) {
                if (selUserList.contains(m.getAuthor())) {
                    displayMessage(m);
                }
            }
        }
    }

    private class ClearSelectionAction extends AbstractAction
    {
        public ClearSelectionAction()
        {
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.META_MASK));
            putValue(LARGE_ICON_KEY, new ImageIcon(ListExampleFrame.class.getResource("/icons/delete_database-32.png")));
            putValue(SMALL_ICON, new ImageIcon(ListExampleFrame.class.getResource("/icons/delete_database-16.png")));
            putValue(NAME, "Clear selection");
            putValue(SHORT_DESCRIPTION, "Unselect selected items");
        }

        @Override
        public void actionPerformed(ActionEvent e)
        {
            output.append("Clear selection action triggered" + newline);
            selectionModel.clearSelection();
        }
    }

    private class KickSelectedAction extends AbstractAction{
        public KickSelectedAction()
        {
            putValue(SMALL_ICON,
                    new ImageIcon(ClientFrame2.class
                            .getResource("/icons/remove_user-16.png")));
            putValue(LARGE_ICON_KEY,
                    new ImageIcon(ClientFrame2.class
                            .getResource("/icons/remove_user-32.png")));
            putValue(ACCELERATOR_KEY,
                    KeyStroke.getKeyStroke(KeyEvent.VK_M,
                            InputEvent.META_MASK));
            putValue(NAME, "Kick selected");
            putValue(SHORT_DESCRIPTION, "Kick selected user(s)");
        }

        /**
         * Opérations réalisées lorsque l'action "kicker les utilisateurs sélectionnés" est sollicitée
         * @param e évènement à l'origine de l'action
         * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
         */
        @Override
        public void actionPerformed(ActionEvent e)
        {
            if (!selectedUsers.isEmpty()) {
                for (int i : selectedUsers) {
                    String currentUser = userStore.get(i);
                    outPW.println("Kick " + currentUser);
                }
            }
        }

    }

    private class SortAction extends AbstractAction {
	    SortType sortType;
	    String name;
	    public SortAction(SortType sortType) {
            this.sortType = sortType;
            switch (sortType) {
                case DATE:
                    this.name = "date";
                    break;
                case AUTHOR:
                    this.name = "author";
                    break;
                case CONTENT:
                    this.name = "content";
                    break;
            }
            putValue(NAME, this.name);
            putValue(SHORT_DESCRIPTION, "Sort by " + this.name);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
	        this.removeOrderTypes();
            switch (this.sortType) {
                case DATE:
                    Message.addOrder(Message.MessageOrder.DATE);
                    break;
                case AUTHOR:
                    Message.addOrder(Message.MessageOrder.AUTHOR);
                    break;
                case CONTENT:
                    Message.addOrder(Message.MessageOrder.CONTENT);
                    break;
            }
            try {
                document.remove(0, document.getLength());
            } catch (BadLocationException e1) {
                e1.printStackTrace();
            }

            Collections.sort(messageStore);
            messageStore.stream().forEach(msgPrinter);

        }

        private void removeOrderTypes() {
            Message.removeOrder(Message.MessageOrder.DATE);
            Message.removeOrder(Message.MessageOrder.AUTHOR);
            Message.removeOrder(Message.MessageOrder.CONTENT);
        }
    }

	/**
	 * Classe gérant la fermeture correcte de la fenêtre. La fermeture correcte
	 * de la fenètre implique de lancer un cleanup
	 */
	protected class FrameWindowListener extends WindowAdapter
	{
		/**
		 * Méthode déclenchée à la fermeture de la fenêtre. Envoie la commande
		 * "bye" au serveur
		 */
		@Override
		public void windowClosing(WindowEvent e)
		{
			logger.info("FrameWindowListener::windowClosing: sending bye ... ");
			/*
			 * appeler actionPerformed de quitAction si celle ci est
			 * non nulle
			 */
			if (quitAction != null)
			{
				quitAction.actionPerformed(null);
			}
		}
	}

	/**
	 * Exécution de la boucle d'exécution. La boucle d'exécution consiste à lire
	 * une ligne sur le flux d'entrée avec un BufferedReader tant qu'une erreur
	 * d'IO n'intervient pas indiquant que le flux a été coupé. Auquel cas on
	 * quitte la boucle principale et on ferme les flux d'I/O avec #cleanup()
	 */
	@Override
	public void run()
	{
		try {
			inOS = new ObjectInputStream(inPipe);
		} catch (IOException e) {
			e.printStackTrace();
		}

		Message messageIn;

		while (commonRun.booleanValue())
		{
			messageIn = null;
			try
			{
				messageIn = (Message) inOS.readObject();
			}
			catch (IOException e)
			{
				logger.warning(e.getMessage());
				logger.warning("ClientFrame: I/O Error reading");
				// break;
				continue;
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}

			if (messageIn != null)
			{
				messageStore.add(messageIn);
                try {
                    document.remove(0, document.getLength());
                    messageStore.stream().sorted().forEach(msgPrinter);
                } catch (BadLocationException e) {
                    e.printStackTrace();
                }
			}
			else // messageIn == null
			{
				break;
			}

		}

		if (commonRun.booleanValue())
		{
			logger
			    .info("ClientFrame::cleanup: changing run state at the end ... ");
			synchronized (commonRun)
			{
				commonRun = Boolean.FALSE;
			}
		}

		cleanup();
	}

	/**
	 * Fermeture de la fenètre et des flux à la fin de l'exécution
	 */
	@Override
	public void cleanup()
	{
		logger.info("ClientFrame::cleanup: closing input buffered reader ... ");
		try
		{
			inOS.close();
		}
		catch (IOException e)
		{
			logger.warning("ClientFrame::cleanup: failed to close input reader"
			    + e.getLocalizedMessage());
		}

		super.cleanup();
	}

	private void displayMessage(Message msg) {
		String author = msg.getAuthor();
		if (author != null) {
            StyleConstants.setForeground(documentStyle, getColorFromName(msg.getAuthor()));
            if ((author.length() > 0) && !userStore.contains(author)) {
                userStore.add(author);
                Collections.sort(userStore);
            }
		} else {
            Pattern disconnectPattern = Pattern.compile("(.*) logged out$");
            Matcher disconnectMatcher = disconnectPattern.matcher(msg.getContent());
            if (disconnectMatcher.matches()) {
                userStore.remove(disconnectMatcher.group(1));
            }
		}

		// Refresh user list interface
        usersList.clear();
        for (String s : userStore)
            usersList.addElement(s);

		try {
			document.insertString(document.getLength(), msg.toString() + Vocabulary.newLine, documentStyle);
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
		StyleConstants.setForeground(documentStyle, defaultColor);
	}

	/**
	 * Color Text renderer for drawing list's usersList in colored text
	 * @author davidroussel
	 */
	public class ColorTextRenderer extends JLabel
			implements ListCellRenderer<String>
	{
		private Color color = null;

		/**
		 * Customized rendering for a ListCell with a color obtained from
		 * the hashCode of the string to display
		 * @see
		 * javax.swing.ListCellRenderer#getListCellRendererComponent(javax.swing
		 * .JList, java.lang.Object, int, boolean, boolean)
		 */
		@Override
		public Component getListCellRendererComponent(
				JList<? extends String> list, String value, int index,
				boolean isSelected, boolean cellHasFocus)
		{
			color = list.getForeground();
			if (value != null)
			{
				if (value.length() > 0)
				{
                    color = getColorFromName(value);
				}
			}
			setText(value);
			if (isSelected)
			{
				setBackground(color);
				setForeground(list.getSelectionForeground());
			}
			else
			{
				setBackground(list.getBackground());
				setForeground(color);
			}
			setEnabled(list.isEnabled());
			setFont(list.getFont());
			setOpaque(true);
			return this;
		}
	}

}
