package com.github.curiousoddman.curious_images.ui.nodes;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;

import java.time.Duration;

public class DurationSpinner extends HBox {

    public enum Unit {
        SECONDS,
        MINUTES,
        HOURS,
        DAYS
    }

    private final ObjectProperty<Unit> defaultUnit = new SimpleObjectProperty<>(Unit.HOURS);

    public final Unit getDefaultUnit() {
        return defaultUnit.get();
    }

    public final void setDefaultUnit(Unit unit) {
        defaultUnit.set(unit);
    }

    public final ObjectProperty<Unit> defaultUnitProperty() {
        return defaultUnit;
    }

    private final Spinner<Integer> valueSpinner = new Spinner<>();
    private final ChoiceBox<Unit>  unitChoice   = new ChoiceBox<>();

    private final ObjectProperty<Duration> duration = new SimpleObjectProperty<>(Duration.ofHours(1));

    private boolean updating;

    public DurationSpinner() {
        super(8);

        setAlignment(Pos.CENTER_LEFT);

        valueSpinner.setEditable(true);
        valueSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, Integer.MAX_VALUE, 1));

        unitChoice.getItems()
                  .setAll(Unit.values());

        getChildren().addAll(valueSpinner, unitChoice);

        duration.addListener((obs, old, value) -> {
            if (!updating) {
                updateUi(value);
            }
        });

        valueSpinner.valueProperty()
                    .addListener((obs, old, value) -> updateDuration());
        unitChoice.valueProperty()
                  .addListener((obs, old, value) -> updateDuration());

        setDuration(Duration.ofHours(1));
    }

    public ObjectProperty<Duration> durationProperty() {
        return duration;
    }

    public Duration getDuration() {
        return duration.get();
    }

    public void setDuration(Duration duration) {
        this.duration.set(duration);
    }

    private void updateUi(Duration duration) {
        updating = true;

        Unit unit;
        int  value;

        if (duration.toDays() > 0 && duration.toHours() % 24 == 0) {
            unit = Unit.DAYS;
            value = (int) duration.toDays();
        } else if (duration.toHours() > 0 && duration.toMinutes() % 60 == 0) {
            unit = Unit.HOURS;
            value = (int) duration.toHours();
        } else if (duration.toMinutes() > 0 && duration.toSeconds() % 60 == 0) {
            unit = Unit.MINUTES;
            value = (int) duration.toMinutes();
        } else {
            unit = Unit.SECONDS;
            value = (int) duration.toSeconds();
        }

        valueSpinner.getValueFactory()
                    .setValue(value);
        unitChoice.setValue(unit);

        updating = false;
    }

    private void updateDuration() {
        if (updating || unitChoice.getValue() == null) {
            return;
        }

        updating = true;

        int value = valueSpinner.getValue();

        Duration d = switch (unitChoice.getValue()) {
            case SECONDS -> Duration.ofSeconds(value);
            case MINUTES -> Duration.ofMinutes(value);
            case HOURS -> Duration.ofHours(value);
            case DAYS -> Duration.ofDays(value);
        };

        duration.set(d);

        updating = false;
    }
}
