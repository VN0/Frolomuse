package com.frolo.muse.ui.main.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.viewpager.widget.ViewPager
import com.frolo.ui.FragmentUtils
import com.frolo.muse.R
import com.frolo.muse.model.Library
import com.frolo.muse.repository.Preferences
import com.frolo.muse.ui.ScrolledToTop
import com.frolo.muse.ui.base.BackPressHandler
import com.frolo.muse.ui.base.BaseFragment
import com.frolo.muse.ui.base.FragmentContentInsetsListener
import com.frolo.muse.util.CollectionUtil
import kotlinx.android.synthetic.main.fragment_library.*


class LibraryFragment: BaseFragment(),
        BackPressHandler,
        FragmentContentInsetsListener,
        ScrolledToTop {

    private val preferences: Preferences by prefs()

    private val viewModel by viewModel<LibraryViewModel>()

    /**
     * The sections that are currently being displayed. Used for comparison with the sections
     * stored in the preferences. These sections need to be kept in the memory
     * and not just rely on the saved instance state, because the fragment view can be created
     * on the same fragment instance without providing the saved state.
     */
    private var currentSections: List<@Library.Section Int>? = null

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
        val toolbar: Toolbar = this.tb_actions

        toolbar.setTitle(R.string.nav_library)
        (activity as? AppCompatActivity)?.apply {
            // Required for options menu
            setSupportActionBar(toolbar)
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_KEY_SECTIONS)) {
            // Restoring the sections
            currentSections = savedInstanceState.getIntegerArrayList(STATE_KEY_SECTIONS)
        }

        // Retrieving library sections from the preferences should be a synchronous operation
        val actualSections = preferences.librarySections.filter { section ->
            preferences.isLibrarySectionEnabled(section)
        }

        if (!areSectionListsEqual(currentSections, actualSections)) {
            currentSections = actualSections
            // It's a compelled workaround to prevent the action bar from adding menus
            // of the previous fragments that are not at the current position
            FragmentUtils.removeAllFragmentsNow(childFragmentManager)
        }

        vp_sections.apply {
            adapter = LibraryPageAdapter(context, childFragmentManager, actualSections)
            // Registering OnPageChangeListener should go after the adapter is set up
            addOnPageChangeListener(onPageChangeCallback)
        }

        tl_sections.setupWithViewPager(vp_sections)

        fab_action.setOnClickListener {
            dispatchClickOnFab()
        }

        invalidateFab()
    }

    private fun areSectionListsEqual(
        sections1: List<@Library.Section Int>?,
        sections2: List<@Library.Section Int>?
    ): Boolean {
        if (sections1 == null || sections2 == null) {
            return false
        }
        return CollectionUtil.areListContentsEqual(sections1, sections2)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        observeViewModel(viewLifecycleOwner)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentSections?.also { safeSections ->
            outState.putIntegerArrayList(STATE_KEY_SECTIONS, ArrayList<Int>(safeSections))
        }
    }

    override fun onDestroyView() {
        vp_sections.removeOnPageChangeListener(onPageChangeCallback)
        super.onDestroyView()
    }

    override fun onBackPress(): Boolean {
        val position = vp_sections.currentItem
        val adapter = vp_sections.adapter as? LibraryPageAdapter ?: return false
        val page = adapter.getPageAt(position)
        if (page is BackPressHandler && FragmentUtils.isInForeground(page)) {
            return page.onBackPress()
        }
        return false
    }

    private fun peekCurrentPage(): Fragment? {
        view ?: return null
        val adapter = vp_sections.adapter as? LibraryPageAdapter
        return adapter?.getPageAt(vp_sections.currentItem)
    }

    private fun invalidateFab() {
        val currFragment = peekCurrentPage()
        if (currFragment is ActionButtonCallback && currFragment.requiresActionButton()) {
            currFragment.onDecorateActionButton(fab_action)
            fab_action.show()
        } else {
            fab_action.hide()
        }
    }

    private fun dispatchClickOnFab() {
        val currFragment = peekCurrentPage()
        if (currFragment is ActionButtonCallback && currFragment.requiresActionButton()) {
            currFragment.onHandleActionButtonClick()
        }
    }

    override fun applyContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        fab_action.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            leftMargin += left
            topMargin += top
            rightMargin += right
            bottomMargin += bottom
        }
    }

    private fun observeViewModel(owner: LifecycleOwner) = with(viewModel) {
    }

    override fun scrollToTop() {
        app_bar_layout?.setExpanded(true, true)
        peekCurrentPage()?.also { page ->
            if (page is ScrolledToTop && FragmentUtils.isInForeground(page)) {
                page.scrollToTop()
            }
        }
    }

    companion object {

        private const val LOG_TAG = "LibraryFragment"

        private const val STATE_KEY_SECTIONS = "library_sections"

        // Factory
        fun newInstance() = LibraryFragment()

    }
}