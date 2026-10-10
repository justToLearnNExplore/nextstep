import './styles.css';
import { injectIcons } from './icons';
import { route, startRouter } from './router';
import { demoView, feedView } from './views/feed';
import { joinView } from './views/join';
import { landing } from './views/landing';

injectIcons();
route('/', landing);
route('/join', joinView);
route('/feed', feedView);
route('/demo', demoView);
startRouter();
