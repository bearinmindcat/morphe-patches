package com.hihonor.android.widget;

/**
 * Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Weather uses, answering "absent" off
 * MagicOS (no Honor code). Answered by hand: MagicOS's swipe-row adapter, an Android BaseAdapter whose rows the app
 * builds (generateView) and fills (fillValues) itself; the city list's adapter extends it.
 */
public abstract class BaseSwipeAdapter extends android.widget.BaseAdapter {
    private com.hihonor.android.widget.SwipeDismissCallback dismiss;

    public BaseSwipeAdapter() {
        super();
    }

    public abstract android.view.View generateView(int position, android.view.ViewGroup parent);

    public abstract void fillValues(int position, android.view.View view);

    public abstract int getSwipeLayoutResourceId(int position);

    @Override
    public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
        android.view.View view = convertView != null ? convertView : generateView(position, parent);
        fillValues(position, view);
        return view;
    }

    /** No swipe-away animation off MagicOS: the row goes as the list refreshes. */
    public void deleteItem(android.view.View p0, int p1) {
    }

    public void setDismissCallback(com.hihonor.android.widget.SwipeDismissCallback p0) {
        dismiss = p0;
    }
}
