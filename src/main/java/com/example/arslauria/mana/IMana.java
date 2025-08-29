package com.example.arslauria.mana;

public interface IMana {
    double getCurrent();
    double getMax();
    void setCurrent(double v);
    void setMax(double v);

    /** visible factor 0..1, 1.0 = show full, 0.5 = visually half */
    double getVisibleFactor();
    void setVisibleFactor(double f);

    default double getVisibleAmount() {
        return getCurrent() * getVisibleFactor();
    }
}
