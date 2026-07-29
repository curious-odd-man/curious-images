package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.event.model.UserNotificationEvent;
import com.github.curiousoddman.curious_images.event.payload.NotificationLevel;
import com.github.curiousoddman.curious_images.event.payload.UserNotificationPayload;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.bundle.NotificationMenuItemBundle;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.NotificationMenuItemController;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import com.github.curiousoddman.curious_images.ui.styles.ThemeManager;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Popup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static com.sun.javafx.util.Utils.runOnFxThread;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.EXCLAMATION_CIRCLE_FILL;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.EXCLAMATION_TRIANGLE_FILL;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationsService {
    private final Popup popup = new Popup();

    private final List<UserNotificationPayload> payloads = new ArrayList<>();

    private final FxmlLoader fxmlLoader;

    private Button notificationsMenu;

    public void initialize(Button notificationsMenu) {
        this.notificationsMenu = notificationsMenu;

        popup.setAutoHide(true);   // closes automatically on outside click / focus loss
        popup.setHideOnEscape(true);
        ThemeManager.register(popup.getScene());

        notificationsMenu.setOnAction(this::onNotificationsOpenClick);
    }

    private void onNotificationsOpenClick(ActionEvent actionEvent) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }

        popup.getContent()
             .add(buildPanel());

        // Position just below the button, left-aligned with it.
        Bounds bounds = notificationsMenu.localToScreen(notificationsMenu.getBoundsInLocal());
        popup.show(notificationsMenu.getScene()
                                    .getWindow(), bounds.getMinX(), bounds.getMaxY() + 4);
    }

    private Node buildPanel() {
        HBox hBox = new HBox();
        for (UserNotificationPayload payload : payloads) {
            LoadedFxml<NotificationMenuItemController> loaded = fxmlLoader.load(
                    FxmlView.NOTIFICATIONS_MENU_ITEM,
                    new NotificationMenuItemBundle(
                            getGraphic(payload.getNotificationLevel()),
                            payload.getTitle(),
                            payload.getDescription(),
                            () -> {
                                payloads.remove(payload);
                                updateNotification();
                            }
                    )
            );
            hBox.getChildren()
                .add(loaded.parent());
        }
        return hBox;
    }

    @EventListener
    public void onUserNotificationEvent(UserNotificationEvent event) {
        payloads.add(event.getPayload());

        updateNotification();
    }

    private void updateNotification() {
        runOnFxThread(() -> {
            notificationsMenu.setVisible(!payloads.isEmpty());
            if (!payloads.isEmpty()) {
                NotificationLevel notificationLevel = getNotificationLevel();
                FontIcon          graphic           = getGraphic(notificationLevel);
                graphic.getStyleClass()
                       .add(getCssStyle(notificationLevel));
                notificationsMenu.setGraphic(graphic);
                notificationsMenu.setText(payloads.size() + "");
            }


        });
    }

    private String getCssStyle(NotificationLevel notificationLevel) {
        return switch (notificationLevel) {
            case WARNING -> CssClasses.WARNING_NOTIFICATION_ICON;
            case ERROR -> CssClasses.ERROR_NOTIFICATION_ICON;
        };
    }

    private NotificationLevel getNotificationLevel() {
        OptionalInt max = payloads.stream()
                                  .map(UserNotificationPayload::getNotificationLevel)
                                  .mapToInt(Enum::ordinal)
                                  .max();
        if (max.isEmpty()) {
            return null;
        }

        return NotificationLevel.values()[max.getAsInt()];
    }

    private FontIcon getGraphic(NotificationLevel notificationLevel) {
        FontIcon icon = switch (notificationLevel) {
            case WARNING -> new FontIcon(EXCLAMATION_TRIANGLE_FILL);
            case ERROR -> new FontIcon(EXCLAMATION_CIRCLE_FILL);
        };
        icon.getStyleClass()
            .add(getCssStyle(notificationLevel));
        return icon;
    }
}
