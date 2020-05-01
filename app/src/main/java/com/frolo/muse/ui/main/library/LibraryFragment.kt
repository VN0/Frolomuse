package com.frolo.muse.ui.main.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.viewpager.widget.ViewPager
import com.frolo.muse.R
import com.frolo.muse.model.Library
import com.frolo.muse.repository.Preferences
import com.frolo.muse.ui.base.BackPressHandler
import com.frolo.muse.ui.base.BaseFragment
import com.frolo.muse.ui.base.NoClipping
import com.frolo.muse.ui.main.removeAllFragmentsNow
import kotlinx.android.synthetic.main.fragment_library.*


class LibraryFragment: BaseFragment(),
        BackPressHandler,
        NoClipping {

    private val preferences: Preferences by prefs()

    private var sections: List<@Library.Section Int>? = null

    private val onPageChangeCallback = object : ViewPager.OnPageChangeListener {
        override fun onPageScrollStateChanged(state: Int) = Unit

        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int
        ) {
            // it's better to check only if position offset is in range -0,1..0,1
            if (positionOffset > -0.1 && positionOffset < 0.1) {
                invalidateFab()
            }
        }

        override fun onPageSelected(position: Int) {
            invalidateFab()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (activity as? AppCompatActivity)?.apply {
            setSupportActionBar(tb_actions)
            title = getString(R.string.nav_library)
        }

        // Retrieving library sections from the preferences should be a synchronous operation
        val actualSections = preferences.librarySections
                .filter { section -> preferences.isLibrarySectionEnabled(section) }

        if (actualSections != sections) {
            // It's a compelled workaround to prevent the action bar from adding menus
            // of previous fragments that are not at the current position
            childFragmentManager.removeAllFragmentsNow()
            sections = actualSections
        }

        vp_sections.apply {
            adapter = LibraryPageAdapter(childFragmentManager, context).apply {
                sections = actualSections
            }

            // Registering OnPageChangeListener should go after the adapter is set up
            addOnPageChangeListener(onPageChangeCallback)
        }

        tl_sections.setupWithViewPager(vp_sections)

        fab_action.setOnClickListener {
            val adapter = vp_sections.adapter as? LibraryPageAdapter
            val currFragment = adapter?.getPageAt(vp_sections.currentItem)
            if (currFragment is FabCallback && currFragment.isUsingFab()) {
                currFragment.handleClickOnFab()
            }
        }

        invalidateFab()
    }

    override fun onDestroyView() {
        vp_sections.removeOnPageChangeListener(onPageChangeCallback)
        super.onDestroyView()
    }

    override fun onBackPress(): Boolean {
        val position = vp_sections.currentItem
        val adapter = vp_sections.adapter as? LibraryPageAdapter ?: return false
        val fragment: BackPressHandler = adapter.getPageAt(position) as? BackPressHandler ?: return false
        return fragment.onBackPress()
    }

    private fun invalidateFab() {
        val adapter = vp_sections.adapter as? LibraryPageAdapter
        val currFragment = adapter?.getPageAt(vp_sections.currentItem)
        if (currFragment is FabCallback && currFragment.isUsingFab()) {
            currFragment.decorateFab(fab_action)
            fab_action.show()
        } else {
            fab_action.hide()
        }
    }

    override fun removeClipping(left: Int, top: Int, right: Int, bottom: Int) {
        fab_action.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            leftMargin += left
            topMargin += top
            rightMargin += right
            bottomMargin += bottom
        }
    }

    companion object {

        // Factory
        fun newInstance() = LibraryFragment()

    }
}