package anonymouls.dev.mgcex.app.main.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import anonymouls.dev.mgcex.app.databinding.MainFragmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@ExperimentalStdlibApi
class MainFragment : Fragment() {

    companion object {
        fun newInstance() = MainFragment()
    }

    private lateinit var viewModel: MainViewModel
    private lateinit var binding: MainFragmentBinding

    private fun optimizeObservers(){
        if (this::viewModel.isInitialized){
            viewModel.removeObservers(this)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = MainFragmentBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = this
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (this::viewModel.isInitialized) {
            GlobalScope.launch(Dispatchers.Default) {
                viewModel.reInit(this@MainFragment)
                viewModel.restore()
            }
        }
    }
    override fun onPause() {
        super.onPause()
        optimizeObservers()
    }
    override fun onDetach() {
        super.onDetach()
        optimizeObservers()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProviders.of(this,
                MyViewModelFactory(this.requireActivity()))
                .get(MainViewModel::class.java)
        binding.viewmodel = viewModel
    }

}