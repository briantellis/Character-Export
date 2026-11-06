/*
 * Copyright (c) 2026, DZWNK
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.briantellis.osrsintelligence;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

@Slf4j
class CharacterStateExporterPanel extends PluginPanel
{
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm:ss a");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a");
    private static final int BUTTON_WIDTH = 170;
    private static final String[] DATASET_FILES = {
        "bank.json", "character.json", "diaries.json", "quests.json",
        "inventory.json", "equipment.json", "seed_vault.json", "combat_achievements.json", "collection_log.json",
        "account.json", "grand_exchange.json", "looting_bag.json"
    };

    static final String[] DATASET_KEYS = {
        "bank", "character", "diaries", "quests",
        "inventory", "equipment", "seed_vault", "combat_achievements", "collection_log",
        "account", "grand_exchange", "looting_bag"
    };
    private static final String[] DATASET_LABELS = {
        "Bank", "Stats", "Diaries", "Quests",
        "Inventory", "Equipment", "Seed Vault", "Combat Tasks", "Collection Log",
        "Account", "Grand Exchange", "Looting Bag"
    };
    private static final String[] DATASET_HINTS = {
        "Log in", "Log in", "Log in", "Log in",
        "Log in", "Log in", "Log in", "Log in", "Log in",
        "Log in", "Log in", "Log in"
    };
    private static final String[] DATASET_READY_HINTS = {
        "Open bank",
        "Sync or train",
        "Sync or open diary",
        "Sync or quest",
        "Sync or move item",
        "Sync or change gear",
        "Open vault",
        "Sync or complete task",
        "Open log",
        "Sync or play",
        "Sync or trade",
        "Check bag"
    };

    enum SyncOutcome
    {
        UPDATED,
        UP_TO_DATE,
        UNAVAILABLE,
        FAILED
    }

    private static final class StatusRow
    {
        private final JLabel timeLabel;
        private final JLabel detailLabel;

        private StatusRow(JLabel timeLabel, JLabel detailLabel)
        {
            this.timeLabel = timeLabel;
            this.detailLabel = detailLabel;
        }
    }

    private final Map<String, StatusRow> statusRows = new LinkedHashMap<>();
    private final Map<String, String> datasetFiles = new LinkedHashMap<>();
    private final JLabel directoryLabel;
    private final JLabel accountLabel;
    private volatile Path currentOutputDir;
    private final Object manualSyncLock = new Object();
    private boolean manualSyncInProgress;
    private final Map<String, SyncOutcome> manualSyncOutcomes = new LinkedHashMap<>();

    private final JLabel syncLabel = new JLabel("Sync is off");
    private final JButton linkButton = new JButton("Link this character");
    private final JButton revokeButton = new JButton("Revoke link");

    CharacterStateExporterPanel(Runnable exportAllAction, Runnable linkAction, Runnable revokeAction)
    {
        super(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Account name
        accountLabel = new JLabel("Not logged in");
        accountLabel.setHorizontalAlignment(JLabel.CENTER);
        accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        accountLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
        accountLabel.setAlignmentX(CENTER_ALIGNMENT);
        accountLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, accountLabel.getPreferredSize().height + 4));
        add(accountLabel);

        // Server sync
        syncLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        syncLabel.setAlignmentX(CENTER_ALIGNMENT);
        add(syncLabel);
        linkButton.setAlignmentX(CENTER_ALIGNMENT);
        linkButton.addActionListener(e -> linkAction.run());
        linkButton.setEnabled(false);
        add(linkButton);
        revokeButton.setAlignmentX(CENTER_ALIGNMENT);
        revokeButton.setVisible(false);
        revokeButton.addActionListener(e ->
        {
            if (JOptionPane.showConfirmDialog(this, "Revoke this link? It can't be used again.", "Revoke link", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
            {
                revokeAction.run();
            }
        });
        add(revokeButton);

        // Status header
        JLabel statusHeader = new JLabel("Export Status");
        statusHeader.setHorizontalAlignment(JLabel.CENTER);
        statusHeader.setForeground(Color.WHITE);
        statusHeader.setBorder(new EmptyBorder(0, 0, 6, 0));
        statusHeader.setAlignmentX(CENTER_ALIGNMENT);
        statusHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, statusHeader.getPreferredSize().height + 4));
        add(statusHeader);

        // Dataset status rows
        JPanel statusGrid = new JPanel();
        statusGrid.setLayout(new BoxLayout(statusGrid, BoxLayout.Y_AXIS));
        statusGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statusGrid.setBorder(new EmptyBorder(8, 8, 8, 8));

        for (int i = 0; i < DATASET_KEYS.length; i++)
        {
            final String datasetKey = DATASET_KEYS[i];
            final String datasetFile = DATASET_FILES[i];
            datasetFiles.put(datasetKey, datasetFile);

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);

            JLabel nameLabel = new JLabel(DATASET_LABELS[i]);
            nameLabel.setHorizontalAlignment(JLabel.LEFT);
            nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            nameLabel.setPreferredSize(new Dimension(80, 28));
            row.add(nameLabel, BorderLayout.WEST);

            JPanel statusPanel = new JPanel();
            statusPanel.setOpaque(false);
            statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));

            JLabel timeLabel = new JLabel(DATASET_HINTS[i]);
            timeLabel.setAlignmentX(CENTER_ALIGNMENT);
            timeLabel.setHorizontalAlignment(JLabel.CENTER);
            timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            statusPanel.add(timeLabel);

            JLabel detailLabel = new JLabel(" ");
            detailLabel.setAlignmentX(CENTER_ALIGNMENT);
            detailLabel.setHorizontalAlignment(JLabel.CENTER);
            detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            statusPanel.add(detailLabel);
            row.add(statusPanel, BorderLayout.CENTER);

            statusRows.put(datasetKey, new StatusRow(timeLabel, detailLabel));
            statusGrid.add(row);
            if (i < DATASET_KEYS.length - 1)
            {
                statusGrid.add(Box.createRigidArea(new Dimension(0, 6)));
            }
        }

        statusGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, statusGrid.getPreferredSize().height + 10));
        add(statusGrid);
        add(Box.createRigidArea(new Dimension(0, 10)));

        // Buttons
        JButton exportAllButton = createCenteredButton("Sync Available");
        exportAllButton.addActionListener(e -> exportAllAction.run());
        add(exportAllButton);

        add(Box.createRigidArea(new Dimension(0, 6)));

        // Plugins can't open folders, so the path goes to the clipboard for pasting into a file browser.
        JButton copyFolderButton = createCenteredButton("Copy Folder Path");
        copyFolderButton.addActionListener(e -> copyOutputFolder(copyFolderButton));
        add(copyFolderButton);

        add(Box.createRigidArea(new Dimension(0, 12)));

        // Output directory
        JPanel dirPanel = new JPanel(new BorderLayout());
        dirPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dirPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)
        ));

        JLabel dirTitle = new JLabel("Output folder:");
        dirTitle.setHorizontalAlignment(JLabel.CENTER);
        dirTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        dirPanel.add(dirTitle, BorderLayout.NORTH);

        directoryLabel = new JLabel("osrs-intelligence/");
        directoryLabel.setHorizontalAlignment(JLabel.CENTER);
        directoryLabel.setForeground(Color.WHITE);
        directoryLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
        dirPanel.add(directoryLabel, BorderLayout.CENTER);

        dirPanel.setAlignmentX(CENTER_ALIGNMENT);
        dirPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, dirPanel.getPreferredSize().height + 20));
        add(dirPanel);
        add(Box.createRigidArea(new Dimension(0, 10)));

        // Notes — export trigger guide and collection log caveat.
        // getMaximumSize() is overridden so BoxLayout never expands the panel
        // beyond its actual content height.
        JPanel notesPanel = new JPanel(new BorderLayout())
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        notesPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        notesPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)
        ));

        JLabel notesLabel = new JLabel(
            "<html>"
                + "'Sync Available' exports Stats, Quests, Diaries, Tasks, "
                + "Inventory &amp; Equipment.<br><br>"
                + "Bank &amp; Seed Vault need to be opened in-game.<br><br>"
                + "Collection Log needs to have each entry clicked in-game."
                + "</html>"
        );
        notesLabel.setHorizontalAlignment(JLabel.LEFT);
        notesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        notesPanel.add(notesLabel, BorderLayout.NORTH);
        notesPanel.setAlignmentX(CENTER_ALIGNMENT);
        add(notesPanel);
    }

    void setAccount(String playerName, Path outputDir)
    {
        currentOutputDir = outputDir;
        SwingUtilities.invokeLater(() ->
        {
            accountLabel.setText(playerName);
            accountLabel.setForeground(Color.WHITE);
            directoryLabel.setText("osrs-intelligence/" + playerName + "/");
            for (int i = 0; i < DATASET_KEYS.length; i++)
            {
                StatusRow row = statusRows.get(DATASET_KEYS[i]);
                if (row != null && !hasExistingDatasetFile(DATASET_KEYS[i]))
                {
                    row.timeLabel.setText(DATASET_READY_HINTS[i]);
                    row.timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                    row.timeLabel.setToolTipText(null);
                    row.detailLabel.setText(" ");
                    row.detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                    row.detailLabel.setToolTipText(null);
                }
            }
        });
    }

    void setSyncState(SyncClient.State state, String detail)
    {
        SwingUtilities.invokeLater(() ->
        {
            syncLabel.setText("Sync: " + detail);
            linkButton.setEnabled(state == SyncClient.State.NOT_PAIRED || state == SyncClient.State.ERROR);
            revokeButton.setVisible(state == SyncClient.State.ACTIVE || state == SyncClient.State.PENDING);
            revalidate();
            repaint();
        });
    }

    void clearAccount()
    {
        currentOutputDir = null;
        SwingUtilities.invokeLater(() ->
        {
            accountLabel.setText("Not logged in");
            accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            directoryLabel.setText("osrs-intelligence/");
            synchronized (manualSyncLock)
            {
                manualSyncInProgress = false;
                manualSyncOutcomes.clear();
            }
            for (int i = 0; i < DATASET_KEYS.length; i++)
            {
                StatusRow row = statusRows.get(DATASET_KEYS[i]);
                if (row != null)
                {
                    row.timeLabel.setText(DATASET_HINTS[i]);
                    row.timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                    row.timeLabel.setToolTipText(null);
                    row.detailLabel.setText(" ");
                    row.detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                }
            }
        });
    }

    void beginManualSync()
    {
        synchronized (manualSyncLock)
        {
            manualSyncInProgress = true;
            manualSyncOutcomes.clear();
        }

    }

    void markExported(String datasetKey)
    {
        String time = formatStatusTime(Instant.now());
        String tooltip = formatStatusTooltip(Instant.now(), "Updated");
        SwingUtilities.invokeLater(() ->
        {
            setRowState(datasetKey, time, "Updated", Color.GREEN, tooltip);
        });
        recordManualOutcome(datasetKey, SyncOutcome.UPDATED);
    }

    void markChecked(String datasetKey)
    {
        String time = formatStatusTime(Instant.now());
        String tooltip = formatStatusTooltip(Instant.now(), "Checked");
        SwingUtilities.invokeLater(() ->
        {
            setRowState(datasetKey, time, "Up to date", Color.GREEN, tooltip);
        });
        recordManualOutcome(datasetKey, SyncOutcome.UP_TO_DATE);
    }

    void markUnavailable(String datasetKey, String detail, String tooltip)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (hasExistingDatasetFile(datasetKey))
            {
                StatusRow row = statusRows.get(datasetKey);
                if (row != null)
                {
                    row.timeLabel.setToolTipText(tooltip);
                    row.detailLabel.setToolTipText(tooltip);
                }
            }
            else
            {
                setRowState(
                    datasetKey,
                    "Not available",
                    detail != null ? detail : "Unavailable",
                    ColorScheme.LIGHT_GRAY_COLOR,
                    tooltip
                );
            }
        });
        recordManualOutcome(datasetKey, SyncOutcome.UNAVAILABLE);
    }

    /**
     * Called for datasets the Sync button cannot update (e.g. collection log).
     * Records the sync outcome so the sync-complete counter advances, but leaves
     * the row display exactly as it was.
     */
    void skipDatasetInSync(String datasetKey)
    {
        recordManualOutcome(datasetKey, SyncOutcome.UNAVAILABLE);
    }

    void markFailed(String datasetKey, String tooltip)
    {
        SwingUtilities.invokeLater(() -> setRowState(
            datasetKey,
            "Failed",
            "Try again",
            new Color(196, 64, 64),
            tooltip
        ));
        recordManualOutcome(datasetKey, SyncOutcome.FAILED);
    }

    void restoreFromDisk(Path accountDir)
    {
        if (accountDir == null || !Files.isDirectory(accountDir))
        {
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            for (int i = 0; i < DATASET_KEYS.length; i++)
            {
                Path file = accountDir.resolve(DATASET_FILES[i]);
                if (!Files.exists(file))
                {
                    continue;
                }

                try
                {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    String exportedAt = formatStatusTime(modified);
                    String tooltip = formatStatusTooltip(modified, "Saved");

                    setRowState(DATASET_KEYS[i], exportedAt, "Saved", Color.GREEN, tooltip);
                }
                catch (IOException ignored)
                {
                }
            }
        });
    }

    private static JButton createCenteredButton(String text)
    {
        JButton button = new JButton(text);
        button.setAlignmentX(CENTER_ALIGNMENT);
        Dimension size = new Dimension(BUTTON_WIDTH, 30);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        return button;
    }

    private void setRowState(String datasetKey, String primary, String detail, Color color, String tooltip)
    {
        StatusRow row = statusRows.get(datasetKey);
        if (row == null)
        {
            return;
        }

        row.timeLabel.setText(primary);
        row.timeLabel.setForeground(color);
        row.timeLabel.setToolTipText(tooltip);
        row.detailLabel.setText(detail);
        row.detailLabel.setForeground(color);
        row.detailLabel.setToolTipText(tooltip);
    }

    private boolean hasExistingDatasetFile(String datasetKey)
    {
        Path dir = currentOutputDir;
        String fileName = datasetFiles.get(datasetKey);
        return dir != null && fileName != null && Files.isRegularFile(dir.resolve(fileName));
    }

    private void recordManualOutcome(String datasetKey, SyncOutcome outcome)
    {
        synchronized (manualSyncLock)
        {
            if (!manualSyncInProgress)
            {
                return;
            }

            manualSyncOutcomes.put(datasetKey, outcome);
            if (manualSyncOutcomes.size() == DATASET_KEYS.length)
            {
                manualSyncInProgress = false;
            }
        }
    }

    static String formatStatusTime(Instant instant)
    {
        ZonedDateTime localTime = instant.atZone(ZoneId.systemDefault());
        return localTime.toLocalTime().format(TIME_FMT);
    }

    static String formatStatusTooltip(Instant instant, String prefix)
    {
        ZonedDateTime localTime = instant.atZone(ZoneId.systemDefault());
        return prefix + " " + localTime.format(DATE_TIME_FMT);
    }

    private void copyOutputFolder(JButton button)
    {
        Path dir = currentOutputDir;
        if (dir == null)
        {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(dir.toString()), null);
        button.setText("Copied");
        Timer reset = new Timer(1500, e -> button.setText("Copy Folder Path"));
        reset.setRepeats(false);
        reset.start();
    }
}
